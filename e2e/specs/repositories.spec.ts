/**
 * /user/repositories (저장소끌어오기) 화면 테스트. 셀렉터는 repositories.jsp · sidebar.jsp 의 실제 id/class 를 그대로 쓴다.
 *
 *   #navRepositories               좌측 메뉴 최상위 항목 "저장소끌어오기" (.nav-menu 의 마지막 li, 활성 시 .active)
 *   #searchRepoUrl / #searchBranch 저장소URL / 브랜치명 입력
 *   #btnRepoPull                   끌어오기 버튼 (.search-form__btn--gray, 요청 중 disabled)
 *   #btnInspectDone                점검완료 버튼 (파랑 기본형 — 이번 범위는 배치만, 동작 없음)
 *   #repoCommitBar                 최신 커밋 바 (끌어오기 성공 후 표시)
 *     #repoCommitBranch / #repoCommitSha / #repoCommitMessage / #repoCommitDate / #repoCommitWarn
 *   #repoBreadcrumb                경로 표시 (.repo-breadcrumb__item[data-path] 링크, .repo-breadcrumb__current 현재 폴더)
 *   #repoFileGrid tr.jqgrow        파일 목록 행
 *   td[aria-describedby="repoFileGrid_name"]  이름 셀
 *   .repo-file__dir / .repo-file__file / .repo-file__up  폴더 / 파일(GitHub 새 탭) / 상위(..) 링크
 *
 *   #repoSaveStatus(.repo-save-status, 실패·미지원 시 --error)  폴더 저장 결과 한 줄 (끌어오기 후 표시)
 *
 *   #appDialog(.is-open/.is-warn)  공통 알림 레이어 팝업 (#appDialogMessage / #appDialogConfirm)
 *
 * 이 화면은 기본 alert()/confirm() 을 쓰지 않는다. 기본 대화상자가 뜨면 nativeDialogs 에 쌓여 실패한다.
 *
 * ── 폴더 저장 (설계 §13) — 가짜 showDirectoryPicker ──
 * 앱 주소가 http 라 실제 showDirectoryPicker 가 없고, 있어도 OS 폴더 창은 Playwright 가 조작할 수 없다.
 * 그래서 page.addInitScript 로 가짜를 주입한다(useFakePicker). 메모리 가짜 핸들이며 결과는 window.__fsFake 에 쌓인다.
 *   mode 'abort'  : AbortError (폴더 선택 취소) — beforeEach 기본값. 기존 테스트는 "취소 = 목록만" 으로 흐른다
 *   mode 'memory' : 루트 폴더 'e2e-picked'. files['guiSample/src/a.txt'] = 텍스트, sizes[...] = 바이트 길이
 *   mode 'none'   : showDirectoryPicker 를 지운다 (미지원 브라우저·비보안 주소)
 * 다른 모드는 테스트 안에서 goto 전에 useFakePicker 를 한 번 더 부른다(나중 init script 가 덮어쓴다).
 * __fsFake.filesRequestsAtPick 은 picker 호출 시점까지 나간 /user/repository/files 요청 수다(사용자 활성화 계약 확인용).
 *
 * 아카이브 프레임 (GET /user/repository/archive, 빅엔디언) — buildFrames 가 만든다
 *   MAGIC 'G' 'S' 'R' 'A' 0x01, 이후 레코드
 *   'F' str(path) u32(n) bytes | 'S' str(path) str(reason) | 'E' str(message) | 'Z' u32 u32 u64
 *   str = u32(UTF-8 길이) + 바이트
 *
 * '@github' 태그 테스트는 실제 GitHub API 를 호출한다(끌어오기 1회당 3회, 폴더 저장까지 하면 +1, 비인증 시 시간당 60회).
 * 오프라인·한도 소진 시: npx playwright test --grep-invert "@guardrail|@github"
 */
import { test, expect, Page } from '@playwright/test';

const REPOSITORIES_PATH = '/user/repositories';

/** 파일 목록 API */
const FILES_API = '/user/repository/files';

/** 아카이브(폴더 저장) API */
const ARCHIVE_API = '/user/repository/archive';
const FILES_ROUTE = /\/user\/repository\/files(\?|$)/;
const ARCHIVE_ROUTE = /\/user\/repository\/archive(\?|$)/;

/** 완료판정 대상 저장소 (공개 저장소) */
const TARGET_REPO_URL = 'https://github.com/sunjihun73/guiSample.git';

const repoUrlInput = (p: Page) => p.locator('#searchRepoUrl');
const branchInput = (p: Page) => p.locator('#searchBranch');
const pullBtn = (p: Page) => p.locator('#btnRepoPull');
const inspectBtn = (p: Page) => p.locator('#btnInspectDone');
const gridRows = (p: Page) => p.locator('#repoFileGrid tr.jqgrow');
const NAME_CELL = 'td[aria-describedby="repoFileGrid_name"]';
const nameCells = (p: Page) => gridRows(p).locator(NAME_CELL);

const dialog = (p: Page) => p.locator('#appDialog');
const dialogMessage = (p: Page) => p.locator('#appDialogMessage');
const dialogConfirm = (p: Page) => p.locator('#appDialogConfirm');

const saveStatus = (p: Page) => p.locator('#repoSaveStatus');

/* ── 가짜 showDirectoryPicker ── */

type PickerMode = 'abort' | 'memory' | 'none';

/** 브라우저에서 실행된다(addInitScript). 바깥 변수를 참조하지 말 것. */
function installFakePicker(arg: { mode: PickerMode; files?: Record<string, string> }) {
  const w = window as any;
  const fake: any = {
    mode: arg.mode,
    files: {} as Record<string, string>,
    sizes: {} as Record<string, number>,
    dirs: new Set<string>(),
    pickCount: 0,
    pickOptions: null,
    filesRequests: 0,
    filesRequestsAtPick: -1,
  };
  for (const [k, v] of Object.entries(arg.files || {})) {
    fake.files[k] = v;
    fake.sizes[k] = new TextEncoder().encode(v).length;
    const segs = k.split('/');
    for (let i = 1; i < segs.length; i++) fake.dirs.add(segs.slice(0, i).join('/') + '/');
  }
  w.__fsFake = fake;

  // /user/repository/files 요청 수 (jQuery ajax = XHR). 두 번 설치돼도 한 번만 감싼다.
  const proto = XMLHttpRequest.prototype as any;
  if (!proto.__fsPatched) {
    proto.__fsPatched = true;
    const open = proto.open;
    proto.open = function (method: string, url: string, ...rest: any[]) {
      if (String(url).includes('/user/repository/files') && w.__fsFake) w.__fsFake.filesRequests++;
      return open.call(this, method, url, ...rest);
    };
  }

  const domErr = (name: string, msg: string) => new DOMException(msg, name);
  const checkName = (name: string) => {
    if (!name || name === '.' || name === '..' || /[\/\\\u0000]/.test(name)) {
      throw new TypeError('Name is not allowed: ' + name);
    }
  };

  const makeFile = (name: string, key: string) => ({
    kind: 'file',
    name,
    async createWritable(opts?: { keepExistingData?: boolean }) {
      const parts: Uint8Array[] = [];
      if (opts && opts.keepExistingData && key in fake.files) parts.push(new TextEncoder().encode(fake.files[key]));
      let closed = false;
      return {
        async write(data: any) {
          if (closed) throw domErr('InvalidStateError', 'closed');
          if (data && typeof data === 'object' && 'type' in data && data.type === 'write') data = data.data;
          if (typeof data === 'string') parts.push(new TextEncoder().encode(data));
          else if (data instanceof Blob) parts.push(new Uint8Array(await data.arrayBuffer()));
          else if (data instanceof ArrayBuffer) parts.push(new Uint8Array(data));
          else if (ArrayBuffer.isView(data)) parts.push(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
          else throw new TypeError('unsupported write data');
        },
        async close() {
          closed = true;
          const len = parts.reduce((n, p) => n + p.length, 0);
          const all = new Uint8Array(len);
          let pos = 0;
          for (const p of parts) { all.set(p, pos); pos += p.length; }
          fake.files[key] = new TextDecoder().decode(all);
          fake.sizes[key] = len;
        },
        async abort() { closed = true; },
      };
    },
  });

  const makeDir = (name: string, prefix: string): any => ({
    kind: 'directory',
    name,
    async getDirectoryHandle(child: string, opts?: { create?: boolean }) {
      checkName(child);
      const key = prefix + child;
      if (key in fake.files) throw domErr('TypeMismatchError', 'is a file: ' + key);
      if (!fake.dirs.has(key + '/')) {
        if (!(opts && opts.create)) throw domErr('NotFoundError', 'no dir: ' + key);
        fake.dirs.add(key + '/');
      }
      return makeDir(child, key + '/');
    },
    async getFileHandle(child: string, opts?: { create?: boolean }) {
      checkName(child);
      const key = prefix + child;
      if (fake.dirs.has(key + '/')) throw domErr('TypeMismatchError', 'is a directory: ' + key);
      if (!(key in fake.files) && !(opts && opts.create)) throw domErr('NotFoundError', 'no file: ' + key);
      return makeFile(child, key);
    },
    /** 바로 아래 항목 (실제 FileSystemDirectoryHandle.values() 처럼 핸들을 낸다). */
    async *values() {
      const kinds = new Map<string, 'file' | 'directory'>();
      for (const k of Object.keys(fake.files)) {
        if (!k.startsWith(prefix)) continue;
        const rest = k.slice(prefix.length);
        const i = rest.indexOf('/');
        kinds.set(i < 0 ? rest : rest.slice(0, i), i < 0 ? 'file' : 'directory');
      }
      for (const d of fake.dirs) {
        if (!d.startsWith(prefix)) continue;
        const rest = d.slice(prefix.length);
        const i = rest.indexOf('/');
        if (i > 0) kinds.set(rest.slice(0, i), 'directory');
      }
      for (const [n, kind] of kinds) {
        yield kind === 'file' ? makeFile(n, prefix + n) : makeDir(n, prefix + n + '/');
      }
    },
  });

  if (arg.mode === 'none') {
    try { delete w.showDirectoryPicker; } catch { /* 무시 */ }
    w.showDirectoryPicker = undefined;
    return;
  }

  w.showDirectoryPicker = function (options: any) {
    fake.pickCount++;
    fake.pickOptions = options || null;
    fake.filesRequestsAtPick = fake.filesRequests;
    if (fake.mode === 'abort') return Promise.reject(domErr('AbortError', 'The user aborted a request.'));
    return Promise.resolve(makeDir('e2e-picked', ''));
  };
}

async function useFakePicker(page: Page, mode: PickerMode, files: Record<string, string> = {}) {
  await page.addInitScript(installFakePicker, { mode, files });
}

async function fakeState(page: Page) {
  return page.evaluate(() => {
    const f = (window as any).__fsFake;
    return {
      mode: f.mode as string,
      files: f.files as Record<string, string>,
      sizes: f.sizes as Record<string, number>,
      pickCount: f.pickCount as number,
      pickOptions: f.pickOptions as any,
      filesRequests: f.filesRequests as number,
      filesRequestsAtPick: f.filesRequestsAtPick as number,
    };
  });
}

/* ── 아카이브 프레임 빌더 (서버 RepoArchiveFrameWriter 와 같은 형식) ── */

type Frame =
  | { t: 'F'; path: string; data: string | Buffer }
  | { t: 'S'; path: string; reason: string }
  | { t: 'E'; message: string }
  | { t: 'Z'; files: number; skipped: number; bytes: number };

function u32(n: number) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
}
function str(s: string) {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([u32(b.length), b]);
}
function buildFrames(frames: Frame[]): Buffer {
  const out: Buffer[] = [Buffer.from([0x47, 0x53, 0x52, 0x41, 0x01])];
  for (const f of frames) {
    if (f.t === 'F') {
      const data = typeof f.data === 'string' ? Buffer.from(f.data, 'utf8') : f.data;
      out.push(Buffer.from('F'), str(f.path), u32(data.length), data);
    } else if (f.t === 'S') {
      out.push(Buffer.from('S'), str(f.path), str(f.reason));
    } else if (f.t === 'E') {
      out.push(Buffer.from('E'), str(f.message));
    } else {
      const big = Buffer.alloc(8);
      big.writeBigUInt64BE(BigInt(f.bytes));
      out.push(Buffer.from('Z'), u32(f.files), u32(f.skipped), big);
    }
  }
  return Buffer.concat(out);
}

/* ── 가짜 /files 응답 ── */

const FAKE_COMMIT = '0123456789abcdef0123456789abcdef01234567';

function fakeFilesResponse() {
  const web = (p: string) => 'https://github.com/sunjihun73/guiSample/blob/' + FAKE_COMMIT + '/' + p;
  const rows = [
    { path: 'src', name: 'src', parentPath: '', type: 'DIR', size: null, depth: 1, webUrl: null },
    { path: 'README.md', name: 'README.md', parentPath: '', type: 'FILE', size: 3, depth: 1, webUrl: web('README.md') },
    { path: 'src/a.txt', name: 'a.txt', parentPath: 'src', type: 'FILE', size: 5, depth: 2, webUrl: web('src/a.txt') },
  ];
  return {
    success: true,
    page: 1,
    total: 1,
    records: rows.length,
    rows,
    summary: {
      repoUrl: TARGET_REPO_URL,
      owner: 'sunjihun73',
      repo: 'guiSample',
      branch: 'main',
      defaultBranch: 'main',
      commitId: FAKE_COMMIT,
      commitMessage: 'e2e fake commit',
      commitDate: '2026-10-19T00:00:00Z',
      truncated: false,
      entryCount: rows.length,
    },
  };
}

/** /files 는 가짜 트리, /archive 는 주어진 프레임으로 채운다. 요청 URL 들을 돌려준다(GitHub 미호출). */
async function routeFakeRepo(page: Page, archiveBody: Buffer) {
  const archiveUrls: string[] = [];
  await page.route(FILES_ROUTE, (route) => route.fulfill({ json: fakeFilesResponse() }));
  await page.route(ARCHIVE_ROUTE, (route) => {
    archiveUrls.push(route.request().url());
    return route.fulfill({
      status: 200,
      contentType: 'application/octet-stream',
      headers: { 'Cache-Control': 'no-store' },
      body: archiveBody,
    });
  });
  return archiveUrls;
}

async function clickPull(page: Page, repoUrl = TARGET_REPO_URL, branch = 'main') {
  await repoUrlInput(page).fill(repoUrl);
  await branchInput(page).fill(branch);
  await pullBtn(page).click();
}

async function expectWarnDialog(page: Page, text: string | RegExp) {
  await expect(dialog(page)).toHaveClass(/is-open/);
  await expect(dialog(page)).toHaveClass(/is-warn/);
  await expect(dialogMessage(page)).toContainText(text);
}

async function closeDialog(page: Page) {
  await dialogConfirm(page).click();
  await expect(dialog(page)).not.toHaveClass(/is-open/);
}

/** 입력을 채우고 끌어오기를 눌러 응답이 돌아올 때까지 기다린다. 응답 JSON 을 돌려준다. */
async function pull(page: Page, repoUrl: string, branch = '') {
  await repoUrlInput(page).fill(repoUrl);
  await branchInput(page).fill(branch);

  const response = page.waitForResponse(
    (res) => res.url().includes(FILES_API) && res.request().method() === 'GET'
  );
  await pullBtn(page).click();
  const res = await response;
  expect(res.status()).toBe(200);
  await expect(pullBtn(page)).toBeEnabled();
  return res.json();
}

test.describe('저장소끌어오기 화면', () => {
  /** 기본 alert()/confirm() 이 뜨면 여기 쌓인다 — 비어 있어야 정상이다. */
  let nativeDialogs: string[];

  test.beforeEach(async ({ page }) => {
    // 기본은 "폴더 선택 취소" — 기존 목록 테스트는 저장 없이 그대로 흐른다(회귀 확인).
    await useFakePicker(page, 'abort');

    nativeDialogs = [];
    page.on('dialog', async (d) => {
      nativeDialogs.push(d.message());
      await d.dismiss();
    });
  });

  test.afterEach(() => {
    expect(nativeDialogs).toEqual([]);
  });

  test('좌측 메뉴 마지막 최상위 항목이 저장소끌어오기이고 화면으로 이동한다', async ({ page }) => {
    await page.goto('/user/dashboard');

    const lastTopItem = page.locator('#sidebar .nav-menu > li').last();
    await expect(lastTopItem).toHaveId('navRepositories');
    await expect(lastTopItem.locator('.nav-label')).toHaveText('저장소끌어오기');
    // 워크플로우 하위 메뉴에 들어가지 않았다.
    await expect(page.locator('#navWorkflowSubmenu #navRepositories')).toHaveCount(0);

    await lastTopItem.locator('a').click();
    await expect(page).toHaveURL(new RegExp(REPOSITORIES_PATH + '$'));
    await expect(page.locator('#navRepositories')).toHaveClass(/active/);
  });

  test('화면 요소가 보인다 — 제목, 입력, 끌어오기(회색)·점검완료(파랑), 그리드', async ({ page }) => {
    await page.goto(REPOSITORIES_PATH);

    await expect(page.locator('.page-header__title')).toHaveText('저장소끌어오기');
    await expect(repoUrlInput(page)).toBeVisible();
    await expect(branchInput(page)).toBeVisible();

    await expect(pullBtn(page)).toBeVisible();
    await expect(pullBtn(page)).toHaveClass(/search-form__btn--gray/);

    await expect(inspectBtn(page)).toBeVisible();
    await expect(inspectBtn(page)).toHaveClass(/search-form__btn/);
    await expect(inspectBtn(page)).not.toHaveClass(/search-form__btn--gray/);

    await expect(page.locator('#repoFileGrid')).toBeAttached();
    await expect(gridRows(page)).toHaveCount(0);
    await expect(page.locator('#repoCommitBar')).toBeHidden();
  });

  test('저장소URL 을 비우고 끌어오면 경고만 뜨고 서버를 부르지 않는다', async ({ page }) => {
    await page.goto(REPOSITORIES_PATH);

    let called = false;
    page.on('request', (req) => {
      if (req.url().includes(FILES_API)) called = true;
    });

    await repoUrlInput(page).fill('   ');
    await pullBtn(page).click();

    await expectWarnDialog(page, '저장소URL 을 입력하세요');
    await closeDialog(page);
    expect(called).toBe(false);
  });

  test('지원하지 않는 저장소 URL 이면 실패 경고가 뜨고 그리드는 비어 있다', async ({ page }) => {
    await page.goto(REPOSITORIES_PATH);

    const data = await pull(page, 'https://gitlab.com/a/b');
    expect(data.success).toBe(false);
    expect(data.rows).toEqual([]);

    await expectWarnDialog(page, '지원하지 않는');
    await closeDialog(page);
    await expect(gridRows(page)).toHaveCount(0);
    await expect(page.locator('#repoCommitBar')).toBeHidden();
  });


  test.describe('폴더 저장 (가짜 picker·가짜 응답)', () => {
    test('저장 성공 — 덮어쓰기, 저장소 이름 폴더, 경로 이탈 차단, 같은 커밋으로 요청', async ({ page }) => {
      await useFakePicker(page, 'memory', { 'guiSample/README.md': 'old', 'guiSample/keep.txt': 'local' });
      const archiveUrls = await routeFakeRepo(
        page,
        buildFrames([
          { t: 'F', path: 'README.md', data: 'new' },
          { t: 'F', path: 'src/a.txt', data: 'hello' },
          { t: 'F', path: '../evil.txt', data: 'evil' },
          { t: 'S', path: 'x.lnk', reason: '허용되지 않는 경로' },
          { t: 'Z', files: 3, skipped: 1, bytes: 12 },
        ])
      );
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);

      // 같은 이름 폴더가 이미 있고 비어 있지 않으면 덮어쓰기를 먼저 묻는다.
      await expect(dialog(page)).toHaveClass(/is-open/);
      await expect(dialog(page)).toHaveClass(/is-warn/);
      await expect(dialogMessage(page)).toContainText("'e2e-picked/guiSample' 폴더가 이미 있고");
      expect(archiveUrls).toHaveLength(0);
      await dialogConfirm(page).click();

      await expect(dialog(page)).toHaveClass(/is-open/);
      await expect(dialog(page)).not.toHaveClass(/is-warn/);
      await expect(dialogMessage(page)).toContainText('파일 2개');
      await expect(dialogMessage(page)).toContainText('e2e-picked/guiSample');
      await closeDialog(page);
      await expect(pullBtn(page)).toBeEnabled();
      await expect(page.locator('#btnRepoPullLabel')).toHaveText('끌어오기');

      const st = await fakeState(page);
      expect(st.files['guiSample/README.md']).toBe('new');          // 덮어씀
      expect(st.files['guiSample/src/a.txt']).toBe('hello');
      expect(st.files['guiSample/keep.txt']).toBe('local');         // 저장소에 없는 로컬 파일은 지우지 않는다
      expect(Object.keys(st.files).some((k) => k.includes('evil'))).toBe(false);
      expect(st.pickOptions).toMatchObject({ mode: 'readwrite' });

      expect(archiveUrls).toHaveLength(1);
      const u = new URL(archiveUrls[0]);
      expect(u.pathname).toContain(ARCHIVE_API);
      expect(u.searchParams.get('ref')).toBe(FAKE_COMMIT);
      expect(u.searchParams.get('repoUrl')).toBe(TARGET_REPO_URL);

      await expect(saveStatus(page)).toBeVisible();
      await expect(saveStatus(page)).toContainText('e2e-picked/guiSample');
      await expect(saveStatus(page)).not.toHaveClass(/repo-save-status--error/);
      await expect(gridRows(page)).not.toHaveCount(0);
    });

    test('기존 폴더 덮어쓰기를 취소하면 내려받지 않고 기존 파일이 그대로다', async ({ page }) => {
      await useFakePicker(page, 'memory', { 'guiSample/README.md': 'old' });
      const archiveUrls = await routeFakeRepo(
        page,
        buildFrames([{ t: 'F', path: 'README.md', data: 'new' }, { t: 'Z', files: 1, skipped: 0, bytes: 3 }])
      );
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);
      await expect(dialog(page)).toHaveClass(/is-open/);
      await expect(dialogMessage(page)).toContainText('계속할까요?');
      await page.locator('#appDialogCancel').click();
      await expect(dialog(page)).not.toHaveClass(/is-open/);

      await expect(saveStatus(page)).toContainText('덮어쓰기 취소');
      await expect(pullBtn(page)).toBeEnabled();
      await expect(page.locator('#btnRepoPullLabel')).toHaveText('끌어오기');
      expect(archiveUrls).toHaveLength(0);
      const st = await fakeState(page);
      expect(st.files['guiSample/README.md']).toBe('old');
      await expect(gridRows(page)).not.toHaveCount(0);
    });

    test('대소문자만 다른 기존 Git 작업 폴더면 경고 문구에 실제 이름과 .git 을 알린다', async ({ page }) => {
      // macOS·Windows 에서는 'guiSample' 이 기존 'GuiSample' 폴더로 열린다 — 작업 중인 프로젝트를 덮어쓰는 사고 방지.
      await useFakePicker(page, 'memory', { 'GuiSample/.git/HEAD': 'ref: refs/heads/main', 'GuiSample/build.gradle': 'x' });
      const archiveUrls = await routeFakeRepo(page, buildFrames([{ t: 'Z', files: 0, skipped: 0, bytes: 0 }]));
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);
      await expect(dialog(page)).toHaveClass(/is-open/);
      await expect(dialog(page)).toHaveClass(/is-warn/);
      await expect(dialogMessage(page)).toContainText("'e2e-picked/GuiSample' 폴더가 이미 있고");
      await expect(dialogMessage(page)).toContainText('대소문자만 다른');
      await expect(dialogMessage(page)).toContainText('.git');
      await page.locator('#appDialogCancel').click();
      expect(archiveUrls).toHaveLength(0);
    });

    test('폴더 선택을 취소하면 목록만 보이고 저장 요청·대화상자가 없다', async ({ page }) => {
      const archiveUrls = await routeFakeRepo(page, buildFrames([{ t: 'Z', files: 0, skipped: 0, bytes: 0 }]));
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);

      await expect(saveStatus(page)).toBeVisible();
      await expect(saveStatus(page)).toContainText('취소');
      await expect(pullBtn(page)).toBeEnabled();
      await expect(gridRows(page)).not.toHaveCount(0);
      await expect(dialog(page)).not.toHaveClass(/is-open/);
      expect(archiveUrls).toHaveLength(0);
      expect((await fakeState(page)).pickCount).toBe(1);
    });

    test('폴더 저장 미지원이면 목록 표시 후 경고하고 저장 요청이 없다', async ({ page }) => {
      await useFakePicker(page, 'none');
      const archiveUrls = await routeFakeRepo(page, buildFrames([{ t: 'Z', files: 0, skipped: 0, bytes: 0 }]));
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);

      await expectWarnDialog(page, '폴더 저장');
      await expect(gridRows(page)).not.toHaveCount(0);
      await closeDialog(page);
      await expect(saveStatus(page)).toHaveClass(/repo-save-status--error/);
      expect(archiveUrls).toHaveLength(0);
    });

    test('서버 오류 프레임(E)이면 메시지로 경고하고 그리드는 유지된다', async ({ page }) => {
      await useFakePicker(page, 'memory');
      await routeFakeRepo(
        page,
        buildFrames([
          { t: 'F', path: 'a.txt', data: 'A' },
          { t: 'E', message: '저장소를 찾을 수 없음' },
        ])
      );
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);

      await expectWarnDialog(page, '저장소를 찾을 수 없음');
      await expect(dialogMessage(page)).toContainText('1개까지 저장됨');
      await closeDialog(page);
      await expect(gridRows(page)).not.toHaveCount(0);
      await expect(page.locator('#repoCommitBar')).toBeVisible();
      await expect(saveStatus(page)).toContainText('저장 실패');
      await expect(pullBtn(page)).toBeEnabled();
    });

    test('종료 레코드 없이 끝난 스트림은 경고한다', async ({ page }) => {
      await useFakePicker(page, 'memory');
      await routeFakeRepo(page, buildFrames([{ t: 'F', path: 'a.txt', data: 'A' }]));
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);

      await expectWarnDialog(page, '끊겼');
      await closeDialog(page);
      await expect(saveStatus(page)).toContainText('저장 실패');
    });

    test('폴더 선택창은 트리 조회보다 먼저 열린다 (사용자 활성화 계약)', async ({ page }) => {
      await useFakePicker(page, 'memory');
      await routeFakeRepo(page, buildFrames([{ t: 'Z', files: 0, skipped: 0, bytes: 0 }]));
      await page.goto(REPOSITORIES_PATH);

      await clickPull(page);
      await expect(dialog(page)).toHaveClass(/is-open/);
      await closeDialog(page);

      const st = await fakeState(page);
      expect(st.pickCount).toBe(1);
      expect(st.filesRequestsAtPick).toBe(0);
      expect(st.filesRequests).toBe(1);
    });
  });

  test.describe('GitHub 저장소', { tag: '@github' }, () => {
    test('브랜치를 지정해 끌어오면 폴더 탐색형 목록과 커밋 바가 보인다', async ({ page }) => {
      await page.goto(REPOSITORIES_PATH);

      const data = await pull(page, TARGET_REPO_URL, 'main');
      expect(data.success).toBe(true);
      expect(data.records).toBeGreaterThan(0);
      expect(data.summary.branch).toBe('main');

      // 루트 — 폴더 먼저, src 폴더와 build.gradle 파일이 있다.
      await expect(nameCells(page).filter({ hasText: /^src$/ }).locator('.repo-file__dir')).toHaveCount(1);
      await expect(
        nameCells(page).filter({ hasText: /^build\.gradle$/ }).locator('.repo-file__file')
      ).toHaveCount(1);
      await expect(page.locator('#repoFileGrid .repo-file__up')).toHaveCount(0);

      // 파일 링크는 GitHub 새 탭
      const fileLink = page.locator('#repoFileGrid .repo-file__file').filter({ hasText: /^build\.gradle$/ });
      await expect(fileLink).toHaveAttribute('target', '_blank');
      await expect(fileLink).toHaveAttribute('href', /^https:\/\/github\.com\/sunjihun73\/guiSample\/blob\//);

      // 커밋 바
      await expect(page.locator('#repoCommitBar')).toBeVisible();
      await expect(page.locator('#repoCommitBranch')).toHaveText('main');
      await expect(page.locator('#repoCommitSha')).toHaveText(/^[0-9a-f]{7}$/);

      // 경로 표시 — 루트는 저장소 이름
      await expect(page.locator('#repoBreadcrumb .repo-breadcrumb__current')).toHaveText('guiSample');

      // src 로 이동 → main 폴더, 맨 위 .. 행
      await page.locator('#repoFileGrid .repo-file__dir').filter({ hasText: /^src$/ }).click();
      await expect(page.locator('#repoBreadcrumb .repo-breadcrumb__current')).toHaveText('src');
      await expect(gridRows(page).first().locator('.repo-file__up')).toHaveCount(1);
      await expect(page.locator('#repoFileGrid .repo-file__dir').filter({ hasText: /^main$/ })).toHaveCount(1);

      // .. 로 복귀
      await page.locator('#repoFileGrid .repo-file__up').click();
      await expect(page.locator('#repoBreadcrumb .repo-breadcrumb__current')).toHaveText('guiSample');
      await expect(page.locator('#repoFileGrid .repo-file__dir').filter({ hasText: /^src$/ })).toHaveCount(1);

      // 경로 표시 링크로도 복귀할 수 있다
      await page.locator('#repoFileGrid .repo-file__dir').filter({ hasText: /^src$/ }).click();
      await page.locator('#repoFileGrid .repo-file__dir').filter({ hasText: /^main$/ }).click();
      await page.locator('#repoBreadcrumb .repo-breadcrumb__item[data-path="src"]').click();
      await expect(page.locator('#repoBreadcrumb .repo-breadcrumb__current')).toHaveText('src');
    });

    test('브랜치를 비우면 기본 브랜치(main)로 끌어온다', async ({ page }) => {
      await page.goto(REPOSITORIES_PATH);

      const data = await pull(page, TARGET_REPO_URL, '');
      expect(data.success).toBe(true);
      expect(data.summary.branch).toBe('main');

      await expect(page.locator('#repoCommitBranch')).toHaveText('main');
      // 입력란은 자동으로 채우지 않는다(설계 A5).
      await expect(branchInput(page)).toHaveValue('');
      await expect(gridRows(page).first()).toBeVisible();
    });

    test('끌어오기로 고른 폴더/guiSample 아래에 같은 커밋의 파일을 저장한다', async ({ page }) => {
      test.setTimeout(180_000);
      await useFakePicker(page, 'memory');
      await page.goto(REPOSITORIES_PATH);

      const filesResponse = page.waitForResponse(
        (res) => res.url().includes(FILES_API) && res.request().method() === 'GET'
      );
      await clickPull(page, TARGET_REPO_URL, 'main');
      const data = await (await filesResponse).json();
      expect(data.success).toBe(true);

      await expect(dialog(page)).toHaveClass(/is-open/, { timeout: 150_000 });
      await expect(dialog(page)).not.toHaveClass(/is-warn/);
      const message = (await dialogMessage(page).textContent()) || '';
      const m = message.match(/파일 (\d+)개/);
      expect(m).not.toBeNull();
      const savedCount = Number(m![1]);
      expect(savedCount).toBeGreaterThan(300);
      await closeDialog(page);

      const st = await fakeState(page);
      const keys = Object.keys(st.files);
      expect(keys.length).toBe(savedCount);
      expect(keys.every((k) => k.startsWith('guiSample/'))).toBe(true);
      expect(keys.some((k) => k.startsWith('guiSample/src/main/'))).toBe(true);

      // 그리드와 저장 내용이 같은 커밋 — build.gradle 크기가 트리 응답의 size 와 같다.
      const buildRow = data.rows.find((r: any) => r.path === 'build.gradle');
      expect(buildRow).toBeTruthy();
      expect(st.sizes['guiSample/build.gradle']).toBe(buildRow.size);
    });
  });
});
