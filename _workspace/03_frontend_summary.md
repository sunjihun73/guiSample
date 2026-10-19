# 03 프론트 요약 — 저장소끌어오기 (2026-10-19)

## 화면 라우트·메뉴
| 라우트 | 뷰 | activeMenu |
|---|---|---|
| GET `/user/repositories` | `views/user/repositories.jsp` | `repositories` |

- `views/common/sidebar.jsp`: `.nav-menu` 의 **마지막 최상위** `li#navRepositories.nav-menu__item`(라벨·툴팁 "저장소끌어오기") 추가. 워크플로우 그룹 뒤에 두었고 `#navWorkflowSubmenu` 안에는 넣지 않았다. 상단 주석의 activeMenu 목록에 `repositories` 추가.
- 라우트(`MainController`)는 backend 담당.

## 호출 엔드포인트와 사용 필드
| 엔드포인트 | 사용 필드 |
|---|---|
| GET `/user/repository/files?repoUrl=&branch=` (jQuery ajax, `cache:false`) | `success`, `message`(실패), `rows[]`.`path`/`name`/`parentPath`/`type`/`size`/`webUrl`, `summary`.`repo`/`branch`/`commitId`/`commitMessage`/`commitDate`/`truncated`/`entryCount` |

- 미사용: `page`/`total`/`records`(그리드는 `datatype:"local"` 로 현재 폴더만 클라이언트 페이징), `rows[].depth`, `summary.repoUrl`/`owner`/`defaultBranch`.
- 필드명은 `dto/RepoFileRow`, `dto/RepoTreeSummary` record 컴포넌트와 대조 확인.

## 동작
- 끌어오기: URL 공백이면 `appDialog.warn` 후 중단(서버 호출 없음). 요청 중 `#btnRepoPull` disabled + 라벨 "끌어오는 중…". `success:false`/AJAX 오류 → 화면 초기화 + `appDialog.warn`(401/403 은 csrf.js 의 새로고침에 맡김).
- 성공: rows 를 `parentPath` 로 묶어 보관 → 루트 표시 → 커밋 바(브랜치·SHA 7자리·메시지 첫 줄·로컬 시각, truncated 면 경고) → 경로 표시.
- 폴더/`..`/경로 링크 클릭은 클라이언트에서만 처리(추가 서버 호출 없음). 파일은 `webUrl` 을 새 탭(`rel="noopener noreferrer"`).
- `#btnInspectDone` 은 배치만(핸들러·disabled·상태 표시 없음, 설계 §12).

## id/class
신규 id: `navRepositories`, `repoSearchForm`, `searchRepoUrl`, `searchBranch`, `btnRepoPull`, `btnRepoPullLabel`, `btnInspectDone`, `repoCommitBar`, `repoCommitBranch`, `repoCommitSha`, `repoCommitMessage`, `repoCommitDate`, `repoCommitWarn`, `repoBreadcrumb`, `repoFileGrid`, `repoFileGridPager`.

신규 class(common.css): `repo-url-input`, `repo-commit-bar`(`__branch`/`__sha`/`__message`/`__date`/`__warn`), `repo-breadcrumb`(`__item`/`__current`/`__sep`), `repo-file__dir`/`__file`/`__up`/`__plain`/`__icon`(`--dir`/`--file`). 기존 클래스 정의 변경 없음.

기존 id/class 변경 없음 → 기존 e2e 영향 없음(`workflow.spec.ts` 의 하위 메뉴 4개 유지).

## e2e
- 신규 `e2e/specs/repositories.spec.ts` (6개). `@github` 태그 2개는 실제 GitHub 호출. 점검완료 시나리오·초기 disabled 검사는 넣지 않음(§12). `package.json` 변경 없음.

## 설계와 다른 점
- 그리드 행 id 는 `path` 가 아니라 순번(`rid` = `r{i}`, `..` 는 `__up__`) — 경로의 특수문자가 DOM id 로 가지 않게. `path` 는 숨김 컬럼으로 유지.
- SUBMODULE 은 폴더 아이콘을 쓰되 이동 링크 없음(트리에 하위 항목이 없음).
- 입력 폭 보조 클래스는 `.search-form__input--wide` 대신 `repo-url-input`(지시: repo-* 만 추가).
- 사이드바 `li` 에 `id="navRepositories"` 추가(e2e 안정 셀렉터).
- 커밋 바·경로 표시는 끌어오기 전 `hidden` 속성으로 숨김.

---

# 후속 1 — 끌어오기 결과를 브라우저 PC 폴더에 저장 (2026-10-19, 설계 §13)

## 화면 라우트·메뉴
변경 없음 — GET `/user/repositories` → `views/user/repositories.jsp`, activeMenu `repositories`.

## 호출 엔드포인트와 사용 필드
| 엔드포인트 | 호출 방식 | 사용 필드 |
|---|---|---|
| GET `/user/repository/files?repoUrl=&branch=` | jQuery ajax (변경 없음) | 기존 필드 + 저장용 `summary.repo`(폴더명), `summary.commitId`(아카이브 `ref`), `rows[].type`(FILE+SYMLINK 수 = 진행 표시 N) |
| GET `/user/repository/archive?repoUrl=&ref={summary.commitId}` | `fetch` + `X-Requested-With: XMLHttpRequest`, `credentials:'same-origin'`, `cache:'no-store'` (GET 이라 CSRF 헤더 없음) | 프레임 스트림 §13.5 (backend 가 그대로 확정): MAGIC `GSRA\x01`, `F` str u32 bytes / `S` str str / `E` str / `Z` u32 u32 u64, 빅엔디언, str = u32 길이 + UTF-8 |

- 401/403 → `location.reload()`(오류 대화상자 생략). MAGIC 불일치(로그인 HTML 등)·`E`·`Z` 없는 종료·HTTP 오류 → 경고.
- `Z` 의 숫자는 읽고 버린다. 화면의 "파일 n개"는 **브라우저가 실제로 쓴 수**(브라우저 경로 재검사로 건너뛴 것·쓰기 실패 제외), 건너뜀 = 서버 `S` + 브라우저 거부.

## 동작 (pullRepository, §13.4 순서)
1. URL 공백 → `appDialog.warn`(picker 호출 없음). 2. 버튼 disabled 면 무시.
3. **`repoArchive.pickDirectory()` 를 동기 호출** — `$btn.prop('disabled')`·라벨 변경·`$.ajax`·`appDialog` 보다 앞. 동기 예외는 `Promise.reject` 로 감싼다.
4. 라벨 "폴더 선택 중…"(미지원이면 바로 "끌어오는 중…") → picker 결과를 `{mode: picked|cancelled(AbortError)|error|unsupported}` 로 정리.
5. "끌어오는 중…" → `/files`. 실패 → 기존과 같이 resetView + 경고, 저장 안 함.
6. 성공 → 그리드·커밋 바 표시 후 모드별: 취소 → `#repoSaveStatus` "저장 안 함 (폴더 선택 취소)"(대화상자 없음) / 미지원 → `appDialog.warn("폴더 저장을 쓸 수 없어 목록만 표시했습니다.\n" + unsupportedReason())` / 오류 → `appDialog.warn("폴더를 열지 못해 목록만 표시했습니다.\n…")` / picked → `repoArchive.save`, 라벨 "저장 중… n/N".
7. 성공 → `appDialog.alert("'{폴더}/{repo}' 에 파일 n개를 저장했습니다." [+ 건너뜀 k건][+ 실패 m건: 앞 5개])`, 상태 "저장: {폴더}/{repo} · 파일 n개 · HH:mm". 실패 → `appDialog.warn("저장 중 오류가 발생했습니다. 파일 n개까지 저장됨\n{메시지}")`, 상태 "저장 실패".
8. 모든 경로 끝에 버튼 활성·라벨 "끌어오기"(저장이 끝날 때까지 disabled 유지).
- `summary.repo` 가 `isSafeSegment` 실패이거나 `commitId` 가 비면 저장 생략 + 경고.

## static/js/repo-archive.js (신규, `window.repoArchive`)
`isSupported()`(함수 존재만 — isSecureContext 로 막지 않음) / `pickDirectory()`(`{id:'guisample-repo-pull', mode:'readwrite', startIn:'documents'}`, Promise 그대로) / `unsupportedReason()`(isSecureContext 로 문구 분기) / `isSafeSegment(name)` / `save(dirHandle, repoName, archiveUrl, onProgress)`.
- 바이트 리더: `ReadableStream` reader + 청크 큐에서 정확히 n바이트, `DataView` 빅엔디언, `getBigUint64` → `Number`, `TextDecoder('utf-8')`.
- 경로 재검사: `split('/')` 모든 세그먼트 `isSafeSegment`(빈 값·`.`·`..`·`/`·`\`·NUL 거부 → 맨 앞 `/`·연속 `/` 도 거부). 실패 → skipped, 계속.
- 순차 쓰기, 폴더 핸들 `Map` 캐시, `createWritable()`(keepExistingData 기본 false = 덮어쓰기) → `write` → `close`, write 실패 시 `abort()`. 파일 하나 실패 → failed 에 넣고 계속. `{repo}` 폴더 생성 실패 → 전체 실패. 저장소에 없는 로컬 파일은 지우지 않는다.
- Node 에서 3바이트 청크 분할 스트림으로 Z/E/불완전/HTML 응답 4가지를 돌려 확인(한글 경로·0바이트·`../x` 거부 포함).

## id/class
- 신규 id: `repoSaveStatus`(`<span role="status" hidden>`, `#repoSearchForm` 바로 뒤).
- 신규 class(common.css 끝): `repo-save-status`, `repo-save-status--error`.
- 기존 id/class 변경 없음. `#btnRepoPullLabel` 문구만 단계별로 바뀜. 점검완료 그대로(핸들러 없음). 페이지 설명문 갱신(셀렉터 의존 없음). `repo-archive.js` 는 `dialog.js` 뒤·인라인 스크립트 앞에 로드.

## e2e (`e2e/specs/repositories.spec.ts`)
- 상단 주석: `#repoSaveStatus`, 가짜 picker 사용법, 프레임 형식 추가.
- 헬퍼: `installFakePicker`(addInitScript 함수, 모드 `abort`/`memory`/`none`, 메모리 핸들 — `window.__fsFake.files`(텍스트)/`sizes`(바이트)/`pickCount`/`pickOptions`/`filesRequests`/`filesRequestsAtPick`, XHR `open` 을 감싸 `/files` 요청 수 기록), `useFakePicker`, `fakeState`, `buildFrames`, `fakeFilesResponse`(commitId 40자 고정), `routeFakeRepo`(`page.route` 로 /files·/archive 가짜 응답, archive 요청 URL 수집), `clickPull`.
- `beforeEach` 첫 줄에 `useFakePicker(page, 'abort')` → 기존 6건은 셀렉터·기대값 그대로("취소 = 목록만").
- 추가 6건(GitHub 미호출): 저장 성공·덮어쓰기·로컬 파일 보존·`../evil.txt` 차단·ref=commitId / 취소 / 미지원 / `E` 프레임 / 불완전 스트림 / picker 가 `/files` 보다 먼저(`filesRequestsAtPick === 0`).
- `@github` 1건: 실제 저장소를 memory 모드로 저장 → 알림의 파일 수 = 가짜 폴더 파일 수(>300), `guiSample/src/main/` 존재, `guiSample/build.gradle` 바이트 길이 = 트리 응답 `size`. `test.setTimeout(180s)`.
- `npx playwright test specs/repositories.spec.ts --list` 로 파싱 확인(13건). 실행은 하지 않음. `package.json` 변경 없음.

## 설계와 다른 점 / 메모
- `#repoSaveStatus` 는 폼 아래 한 줄(커밋 바 안이 아님).
- 미지원·picker 오류도 상태 줄에 "저장 안 함 (…)"(`--error`)을 남긴다(설계는 대화상자만 명시).
- 실제 OS 폴더 저장 완료판정은 보안 컨텍스트(설계 13.10-1) 결정 후 수동 확인 필요.
