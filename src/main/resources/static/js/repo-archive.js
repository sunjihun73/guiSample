/*
 * 저장소끌어오기 — 브라우저 PC 폴더 저장 (설계 §13, ADR 0007 결정 상세 10).
 *
 * File System Access API(window.showDirectoryPicker, Chrome/Edge, 보안 컨텍스트 전용)로 고른 폴더 아래
 * {저장소명}/ 를 만들고, GET /user/repository/archive 의 프레임 스트림을 읽어 파일을 하나씩 쓴다(같은 이름은 덮어씀).
 *
 * 프레임 스트림 (빅엔디언, 서버 RepoArchiveFrameWriter 와 같은 형식)
 *   stream := MAGIC record*            MAGIC = 'G' 'S' 'R' 'A' 0x01
 *   record := 'F' str(path) u32(n) bytes[n]                    파일 1개 (저장소 루트 기준 상대경로)
 *           | 'S' str(path) str(reason)                        서버가 건너뛴 항목
 *           | 'E' str(message)                                 실패 — 마지막 레코드
 *           | 'Z' u32(fileCount) u32(skippedCount) u64(totalBytes)   정상 종료 — 마지막 레코드
 *   str    := u32(byteLen) UTF-8 바이트
 *
 * 공개 API — window.repoArchive
 *   isSupported()           showDirectoryPicker 함수가 있는지만 본다(isSecureContext 로 막지 않는다 — e2e 가짜 주입용).
 *   pickDirectory()         showDirectoryPicker 의 Promise 를 그대로 반환. ★ 클릭 직후, 어떤 await/ajax/appDialog 보다 먼저
 *                           '동기적으로' 불러야 한다(일시적 사용자 활성화가 짧게만 유효 — 늦으면 SecurityError).
 *   unsupportedReason()     미지원 사유 문구
 *   isSafeSegment(name)     폴더/파일 이름 한 칸이 안전한지
 *   save(dirHandle, repoName, archiveUrl, onProgress)
 *       → Promise<{ folderName, repoName, fileCount, skipped:[{path,reason}], failed:[{path,error}], totalBytes }>
 *       'E'·불완전 종료·HTTP 오류는 reject(Error{ message, savedCount, reloading })
 *
 * 저장소 파일 경로·이름은 외부 문자열이다 — 화면에는 textContent / appDialog 로만 내보낸다.
 */
(function (global) {
    'use strict';

    let MAGIC = [0x47, 0x53, 0x52, 0x41, 0x01];   // 'G' 'S' 'R' 'A' v1

    let T_FILE  = 0x46;   // 'F'
    let T_SKIP  = 0x53;   // 'S'
    let T_ERROR = 0x45;   // 'E'
    let T_END   = 0x5A;   // 'Z'

    let PICKER_OPTIONS = { id: 'guisample-repo-pull', mode: 'readwrite', startIn: 'documents' };

    function isSupported() {
        return typeof global.showDirectoryPicker === 'function';
    }

    function pickDirectory() {
        return global.showDirectoryPicker(PICKER_OPTIONS);
    }

    function unsupportedReason() {
        if (!global.isSecureContext) {
            return '이 주소(http)는 보안 연결이 아니어서 브라우저가 폴더 저장을 허용하지 않습니다. https 또는 localhost 로 접속하세요.';
        }
        return 'Chrome 또는 Edge 에서만 폴더 저장을 지원합니다.';
    }

    /* 서버 ScmPaths.safeRelativePath 와 같은 규칙(세그먼트 단위): 빈 값, '.', '..', '/', '\', NUL 거부. */
    function isSafeSegment(name) {
        if (typeof name !== 'string' || name === '') return false;
        if (name === '.' || name === '..') return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) return false;
        return true;
    }

    /** 상대경로를 세그먼트 배열로. 안전하지 않으면 null (맨 앞 '/', 연속 '/', 맨 끝 '/' 는 빈 세그먼트라 거부된다). */
    function safeSegments(path) {
        if (typeof path !== 'string' || path === '') return null;
        let segs = path.split('/');
        for (let i = 0; i < segs.length; i++) {
            if (!isSafeSegment(segs[i])) return null;
        }
        /* 드라이브 표기(C:) — 서버 ScmPaths 와 같은 규칙 */
        if (/^[A-Za-z]:/.test(segs[0])) return null;
        return segs;
    }

    function makeError(message, savedCount, extra) {
        let err = new Error(message);
        err.savedCount = savedCount || 0;
        if (extra) Object.assign(err, extra);
        return err;
    }

    function errorText(e) {
        if (!e) return '알 수 없는 오류';
        let name = e.name && e.name !== 'Error' ? e.name + ': ' : '';
        return name + (e.message || String(e));
    }

    /* ── 바이트 리더 — ReadableStream 에서 정확히 n 바이트씩 꺼낸다 ── */

    function createByteReader(streamReader) {
        let chunks   = [];    // Uint8Array 목록
        let offset   = 0;     // chunks[0] 안에서 읽은 위치
        let buffered = 0;     // 읽지 않은 바이트 수
        let ended    = false;

        async function fill(n) {
            while (buffered < n && !ended) {
                let r = await streamReader.read();
                if (r.done) {
                    ended = true;
                    break;
                }
                if (r.value && r.value.length > 0) {
                    chunks.push(r.value);
                    buffered += r.value.length;
                }
            }
            return buffered >= n;
        }

        function take(n) {
            let out = new Uint8Array(n);
            let pos = 0;
            while (pos < n) {
                let head  = chunks[0];
                let avail = head.length - offset;
                let len   = Math.min(avail, n - pos);
                out.set(head.subarray(offset, offset + len), pos);
                pos += len;
                offset += len;
                if (offset >= head.length) {
                    chunks.shift();
                    offset = 0;
                }
            }
            buffered -= n;
            return out;
        }

        return {
            /** 정확히 n 바이트. 스트림이 먼저 끝나면 incomplete 오류. */
            readExact: async function (n) {
                if (n === 0) return new Uint8Array(0);
                if (!(await fill(n))) throw makeError('INCOMPLETE', 0, { incomplete: true });
                return take(n);
            },
            /** 다음 레코드 타입 바이트. 스트림이 깔끔하게 끝났으면 -1. */
            readTypeOrEnd: async function () {
                if (!(await fill(1))) return -1;
                return take(1)[0];
            },
            cancel: function () {
                try { streamReader.cancel(); } catch (ignore) { /* 이미 끝남 */ }
            }
        };
    }

    let utf8 = new TextDecoder('utf-8');

    async function readU32(br) {
        let b = await br.readExact(4);
        return new DataView(b.buffer, b.byteOffset, 4).getUint32(0, false);
    }

    async function readU64(br) {
        let b = await br.readExact(8);
        return Number(new DataView(b.buffer, b.byteOffset, 8).getBigUint64(0, false));
    }

    async function readStr(br) {
        let len = await readU32(br);
        return utf8.decode(await br.readExact(len));
    }

    /* ── 쓰기 ── */

    /** 상대 폴더 경로('a/b')의 핸들. 만든 것은 cache 에 둔다(키 '' = 저장소 폴더). */
    async function dirFor(cache, segs) {
        let key = '';
        let handle = cache.get('');
        for (let i = 0; i < segs.length; i++) {
            key = key === '' ? segs[i] : key + '/' + segs[i];
            let cached = cache.get(key);
            if (cached) {
                handle = cached;
                continue;
            }
            handle = await handle.getDirectoryHandle(segs[i], { create: true });
            cache.set(key, handle);
        }
        return handle;
    }

    /** 파일 하나 덮어쓰기 — createWritable() 은 빈 임시 파일에 쓰고 close() 때 원본을 바꾼다. */
    async function writeFile(cache, segs, bytes) {
        let dir = await dirFor(cache, segs.slice(0, segs.length - 1));
        let fileHandle = await dir.getFileHandle(segs[segs.length - 1], { create: true });
        let writable = await fileHandle.createWritable();
        try {
            await writable.write(bytes);
        } catch (e) {
            try { await writable.abort(); } catch (ignore) { /* 이미 닫힘 */ }
            throw e;
        }
        await writable.close();
    }

    /**
     * 저장 전 점검 — 고른 폴더에 저장소 이름과 같은 항목이 이미 있는지 (대소문자 무시).
     *
     * macOS·Windows 파일시스템은 대소문자를 구분하지 않아 'guiSample' 이 기존 'GuiSample' 폴더로 열린다.
     * 작업 중인 프로젝트의 상위 폴더를 고르면 그 프로젝트가 통째로 덮어써진다 — 그래서 실제 이름과
     * .git 존재 여부까지 돌려주고, 덮어쓰기 여부는 화면이 사용자에게 묻는다.
     *
     * → Promise<{ exists, name, nonEmpty, hasGit }>  (점검 자체가 실패하면 exists·nonEmpty=true 로 본다)
     */
    async function inspectTarget(dirHandle, repoName) {
        let lower = String(repoName).toLowerCase();
        try {
            let found = null;
            for await (let entry of dirHandle.values()) {
                if (entry.name.toLowerCase() === lower) { found = entry; break; }
            }
            if (!found) return { exists: false, name: repoName, nonEmpty: false, hasGit: false };
            if (found.kind !== 'directory') return { exists: true, name: found.name, nonEmpty: true, hasGit: false };

            let nonEmpty = false;
            let hasGit = false;
            for await (let child of found.values()) {
                nonEmpty = true;
                if (child.name === '.git') { hasGit = true; break; }
            }
            return { exists: true, name: found.name, nonEmpty: nonEmpty, hasGit: hasGit };
        } catch (e) {
            return { exists: true, name: repoName, nonEmpty: true, hasGit: false, error: errorText(e) };
        }
    }

    /**
     * 아카이브를 받아 dirHandle/{repoName}/ 아래에 저장한다.
     * onProgress(processed) — 'F' 레코드를 하나 처리할 때마다(저장·건너뜀·실패 포함).
     */
    async function save(dirHandle, repoName, archiveUrl, onProgress) {
        if (!isSafeSegment(repoName)) {
            throw makeError('저장소 이름을 폴더 이름으로 쓸 수 없습니다: ' + repoName, 0);
        }

        let repoDir;
        try {
            repoDir = await dirHandle.getDirectoryHandle(repoName, { create: true });
        } catch (e) {
            throw makeError('저장소 폴더(' + repoName + ')를 만들지 못했습니다. ' + errorText(e), 0);
        }

        let res;
        try {
            res = await fetch(archiveUrl, {
                method:      'GET',
                headers:     { 'X-Requested-With': 'XMLHttpRequest' },   // 세션 만료 시 302 대신 401
                credentials: 'same-origin',
                cache:       'no-store'
            });
        } catch (e) {
            throw makeError('파일을 내려받지 못했습니다(네트워크 오류). ' + errorText(e), 0);
        }

        if (res.status === 401 || res.status === 403) {
            global.location.reload();
            throw makeError('세션이 만료되어 다시 로그인합니다.', 0, { reloading: true });
        }
        if (!res.ok || !res.body) {
            throw makeError('파일을 내려받지 못했습니다(HTTP ' + res.status + ').', 0);
        }

        let br = createByteReader(res.body.getReader());
        let cache = new Map();
        cache.set('', repoDir);

        let saved      = 0;
        let processed  = 0;
        let totalBytes = 0;
        let skipped    = [];
        let failed     = [];

        try {
            let magic;
            try {
                magic = await br.readExact(MAGIC.length);
            } catch (e) {
                throw makeError('서버 응답 형식이 올바르지 않습니다.', 0);
            }
            for (let i = 0; i < MAGIC.length; i++) {
                if (magic[i] !== MAGIC[i]) throw makeError('서버 응답 형식이 올바르지 않습니다.', 0);
            }

            for (;;) {
                let type = await br.readTypeOrEnd();

                if (type === -1) {
                    throw makeError('응답이 중간에 끊겼습니다(종료 표시 없음).', saved);
                }

                if (type === T_FILE) {
                    let path  = await readStr(br);
                    let n     = await readU32(br);
                    let bytes = await br.readExact(n);
                    processed++;

                    let segs = safeSegments(path);
                    if (!segs) {
                        skipped.push({ path: path, reason: '허용되지 않는 경로' });
                    } else {
                        try {
                            await writeFile(cache, segs, bytes);
                            saved++;
                            totalBytes += n;
                        } catch (e) {
                            failed.push({ path: path, error: errorText(e) });
                        }
                    }
                    if (onProgress) onProgress(processed);
                    continue;
                }

                if (type === T_SKIP) {
                    let sPath   = await readStr(br);
                    let sReason = await readStr(br);
                    skipped.push({ path: sPath, reason: sReason });
                    continue;
                }

                if (type === T_ERROR) {
                    let message = await readStr(br);
                    throw makeError(message || '저장소 파일을 가져오지 못했습니다.', saved);
                }

                if (type === T_END) {
                    await readU32(br);   // 서버 fileCount — 화면은 실제로 쓴 수를 보인다
                    await readU32(br);   // 서버 skippedCount — skipped 배열에 이미 있다
                    await readU64(br);   // 서버 totalBytes
                    br.cancel();
                    return {
                        folderName: dirHandle.name || '',
                        repoName:   repoName,
                        fileCount:  saved,
                        skipped:    skipped,
                        failed:     failed,
                        totalBytes: totalBytes
                    };
                }

                throw makeError('서버 응답 형식이 올바르지 않습니다(알 수 없는 레코드).', saved);
            }
        } catch (e) {
            br.cancel();
            if (e && e.incomplete) {
                throw makeError('응답이 중간에 끊겼습니다(종료 표시 없음).', saved);
            }
            if (e && typeof e.savedCount === 'number') {
                e.savedCount = Math.max(e.savedCount, saved);
                throw e;
            }
            throw makeError(errorText(e), saved);
        }
    }

    global.repoArchive = {
        isSupported:       isSupported,
        pickDirectory:     pickDirectory,
        unsupportedReason: unsupportedReason,
        isSafeSegment:     isSafeSegment,
        inspectTarget:     inspectTarget,
        save:              save
    };
})(window);
