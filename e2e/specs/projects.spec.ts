/**
 * /user/projects 화면 테스트. 셀렉터는 projects.jsp의 실제 id/class를 그대로 쓴다.
 *
 *   #searchProjectName             조회 조건 - 프로젝트명
 *   #btnSearch                     조회 버튼
 *   #btnCreate                     등록 버튼 (등록 팝업 오픈)
 *   #projectGrid tr.jqgrow         jqGrid 데이터 행
 *   td[aria-describedby="projectGrid_projectName"]  프로젝트명 셀
 *   #projectGridPager .ui-paging-info  "총 N건" 표시 (viewrecords)
 *   #projectPopup.is-open          등록/수정 레이어 팝업 (열림 상태)
 *   #projectPopupTitle             팝업 제목 ("프로젝트 등록" | "프로젝트 수정")
 *   #popupProjectName              프로젝트명 입력
 *   #popupProjectOwnerName         담당자 (readonly — 로그인 사용자명 자동 셋팅)
 *   #popupReqId / #popupRepoUrl / #popupFileNames  요청 ID / 저장소 URL / 파일명 입력
 *   td[aria-describedby="projectGrid_reqId"]  요청 ID 셀 (담당자 컬럼 바로 뒤)
 *   .btn-detail                    행의 "상세" 버튼 (수정 팝업 오픈)
 *   #btnRepoSync                   REPO동기화 버튼
 *   #appDialog.is-open             알림 레이어 팝업 (#appDialogTitle / #appDialogMessage / #appDialogConfirm)
 *   #popupProjectDescription_ifr   프로젝트 설명 - TinyMCE iframe (textarea를 대체)
 *   #btnPopupSave / #btnPopupCancel / #btnPopupClose  저장 / 취소 / 닫기
 *   #btnPopupDelete                삭제 (수정 모드에서만 표시, REPO PUSH 왼쪽, 빨간색)
 *   #btnPopupRepoPush              REPO PUSH (수정 모드에서만 표시, 저장 버튼 바로 왼쪽)
 */
import { test, expect, Page } from '@playwright/test';

const PROJECTS_PATH = process.env.PROJECTS_PATH ?? '/user/projects';

/** 목록 조회 / 저장 API 경로 (jqGrid GET, 등록 POST 모두 같은 URL) */
const PROJECT_API = '/user/project/projects';

/** 조회 시나리오에서 사용할 검색어. project_master 에 이 이름을 포함한 프로젝트가 있어야 한다. */
const SEARCH_KEYWORD = 'nginx';

/** TinyMCE는 팝업이 열린 뒤에 초기화되므로 로컬 static 로딩 시간을 넉넉히 잡는다. */
const EDITOR_TIMEOUT = 20_000;

const searchInput = (p: Page) => p.locator('#searchProjectName');
const searchBtn = (p: Page) => p.locator('#btnSearch');
const createBtn = (p: Page) => p.locator('#btnCreate');
const gridRows = (p: Page) => p.locator('#projectGrid tr.jqgrow');
/** jqGrid 가 각 td 에 붙이는 aria-describedby = <gridId>_<colName> */
const PROJECT_NAME_CELL = 'td[aria-describedby="projectGrid_projectName"]';
const popup = (p: Page) => p.locator('#projectPopup');
const nameInput = (p: Page) => p.locator('#popupProjectName');
const ownerInput = (p: Page) => p.locator('#popupProjectOwnerName');
const saveBtn = (p: Page) => p.locator('#btnPopupSave');
const reqIdInput = (p: Page) => p.locator('#popupReqId');
const repoUrlInput = (p: Page) => p.locator('#popupRepoUrl');
const fileNamesInput = (p: Page) => p.locator('#popupFileNames');

/** TinyMCE 본문 (contenteditable body) */
const descBody = (p: Page) =>
  p.frameLocator('#popupProjectDescription_ifr').locator('body#tinymce');

/** 테스트가 만든 데이터를 지우지 않으므로 고유 마커를 붙여 쌓는다 (e2e/README.md). */
const marker = () => `e2e-${Date.now()}`;

/**
 * 기본 대화상자(alert/confirm) 수집기. 화면의 알림은 모두 레이어 팝업(#appDialog)이므로
 * 여기에 무엇이든 잡히면 기본 alert() 로 되돌아간 회귀다 — 각 테스트가 빈 배열을 기대한다.
 * (핸들러를 붙이면 Playwright 의 자동 dismiss 가 꺼지므로 직접 닫는다.)
 */
function captureAlerts(page: Page): string[] {
  const seen: string[] = [];
  page.on('dialog', async (dialog) => {
    seen.push(dialog.message());
    await dialog.dismiss();
  });
  return seen;
}

/** 조회 버튼을 눌러 목록 응답이 돌아올 때까지 대기 */
async function search(page: Page, projectName = '') {
  await searchInput(page).fill(projectName);

  const response = page.waitForResponse(
    (res) => res.url().includes(PROJECT_API) && res.request().method() === 'GET'
  );
  await searchBtn(page).click();
  await response;

  // jqGrid 로딩 오버레이가 걷혀야 행 렌더링이 끝난 것이다.
  await expect(page.locator('#load_projectGrid')).toBeHidden();
}

/** 등록 팝업을 열고 TinyMCE 초기화까지 기다린다. */
async function openCreatePopup(page: Page) {
  await createBtn(page).click();

  await expect(popup(page)).toHaveClass(/is-open/);
  await expect(popup(page)).toHaveAttribute('aria-hidden', 'false');
  await expect(descBody(page)).toBeVisible({ timeout: EDITOR_TIMEOUT });
}

test.describe('프로젝트 목록 화면', () => {
  let alerts: string[];

  test.beforeEach(async ({ page }) => {
    alerts = captureAlerts(page);

    await page.goto(PROJECTS_PATH);
    await expect(searchInput(page)).toBeVisible();
  });

  test('화면이 열리고 그리드가 표시된다', async ({ page }) => {
    await expect(page.locator('.page-header__title')).toHaveText('프로젝트 목록');
    await expect(page.locator('#projectGrid')).toBeVisible();
    await expect(searchBtn(page)).toBeEnabled();
    await expect(createBtn(page)).toBeEnabled();

    // 상단바에 로그인 사용자 이름이 채워져 있어야 한다.
    await expect(page.locator('.topbar__username')).not.toBeEmpty();
  });

  test('조회 조건에 nginx 를 넣고 조회하면 그리드에 데이터가 조회된다', async ({ page }) => {
    await search(page, SEARCH_KEYWORD);

    // 한 건이라도 조회되면 성공.
    await expect(gridRows(page).first()).toBeVisible();
    expect(await gridRows(page).count()).toBeGreaterThan(0);

    // 조회 조건이 실제로 걸렸는지 — 모든 행의 프로젝트명에 검색어가 들어 있어야 한다.
    // (mapper 의 projectName LIKE '%keyword%' 필터)
    await expect(gridRows(page).locator(PROJECT_NAME_CELL)).toHaveText(
      new RegExp(SEARCH_KEYWORD, 'i')
    );

    // viewrecords 총건수도 0건이 아니어야 한다.
    await expect(page.locator('#projectGridPager .ui-paging-info')).not.toHaveText(/0\s*$/);

    expect(alerts).toEqual([]);
  });

  test('등록 버튼을 누르면 담당자가 로그인 사용자로 채워진 등록 팝업이 열린다', async ({ page }) => {
    const loginName = (await page.locator('.topbar__username').textContent())?.trim() ?? '';
    expect(loginName).not.toBe('');

    await openCreatePopup(page);

    await expect(page.locator('#projectPopupTitle')).toHaveText('프로젝트 등록');
    await expect(nameInput(page)).toHaveValue('');
    // 담당자는 로그인 사용자명으로 자동 셋팅되고 수정할 수 없다.
    await expect(ownerInput(page)).toHaveValue(loginName);
    await expect(ownerInput(page)).not.toBeEditable();
    // 프로젝트 ID 항목은 등록 모드에서 숨김이다.
    await expect(page.locator('#popupProjectIdField')).toBeHidden();
  });

  test('등록 팝업에서 프로젝트를 입력하고 저장하면 목록에서 조회된다', async ({ page }) => {
    // 같은 이름이 쌓여도 구분되도록 마커를 붙인다.
    const projectName = `e2e Test 프로젝트 ${marker()}`;
    // 담당자는 입력하지 않는다 — 서버가 세션 사용자명(상단바와 같은 이름)으로 저장한다.
    const ownerName = (await page.locator('.topbar__username').textContent())?.trim() ?? '';
    const description = 'e2e Test 프로젝트 설명';

    await openCreatePopup(page);

    await nameInput(page).fill(projectName);
    await descBody(page).fill(description);

    const saved = page.waitForResponse(
      (res) => res.url().includes(PROJECT_API) && res.request().method() === 'POST'
    );
    await saveBtn(page).click();

    const response = await saved;
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ success: true });

    // 저장에 성공하면 팝업이 닫히고 그리드가 다시 읽힌다.
    await expect(popup(page)).not.toHaveClass(/is-open/);
    await expect(popup(page)).toHaveAttribute('aria-hidden', 'true');

    // 저장된 프로젝트가 실제로 조회되는지 이름으로 다시 찾는다.
    await search(page, projectName);

    const savedRow = gridRows(page).filter({ hasText: projectName });
    await expect(savedRow).toHaveCount(1);
    await expect(savedRow).toContainText(ownerName);

    // 저장/조회 어느 단계에서도 실패 alert 가 뜨지 않아야 한다.
    expect(alerts).toEqual([]);
  });

  test('요청 ID·저장소 URL·파일명을 등록하고 상세에서 수정할 수 있다', async ({ page }) => {
    const m = marker();
    const projectName = `e2e Test 프로젝트 ${m}`;
    const reqId = `REQ-${m}`;
    const repoUrl = `https://git.example.com/e2e/${m}.git`;
    const fileNames = 'a.java\nb.xml';

    await openCreatePopup(page);
    await nameInput(page).fill(projectName);
    await reqIdInput(page).fill(reqId);
    await repoUrlInput(page).fill(repoUrl);
    await fileNamesInput(page).fill(fileNames);

    const created = page.waitForResponse(
      (res) => res.url().includes(PROJECT_API) && res.request().method() === 'POST'
    );
    await saveBtn(page).click();
    expect(await (await created).json()).toMatchObject({ success: true });
    await expect(popup(page)).not.toHaveClass(/is-open/);

    await search(page, projectName);
    const row = gridRows(page).filter({ hasText: projectName });
    await expect(row).toHaveCount(1);

    // 요청 ID 는 담당자 컬럼 바로 뒤에 표시된다.
    const cols = await row.locator('td').evaluateAll((tds) =>
      tds.filter((td) => (td as HTMLElement).offsetParent !== null)
        .map((td) => td.getAttribute('aria-describedby'))
    );
    expect(cols.indexOf('projectGrid_reqId')).toBe(cols.indexOf('projectGrid_projectOwnerName') + 1);
    await expect(row.locator('td[aria-describedby="projectGrid_reqId"]')).toHaveText(reqId);

    // 상세 → 수정 팝업에 저장된 값이 그대로 채워진다.
    await row.locator('.btn-detail').click();
    await expect(popup(page)).toHaveClass(/is-open/);
    await expect(page.locator('#projectPopupTitle')).toHaveText('프로젝트 수정');
    await expect(reqIdInput(page)).toHaveValue(reqId);
    await expect(repoUrlInput(page)).toHaveValue(repoUrl);
    await expect(fileNamesInput(page)).toHaveValue(fileNames);

    const newReqId = `${reqId}-v2`;
    const newRepoUrl = `${repoUrl}?v=2`;
    const newFileNames = 'c.jsp';
    await reqIdInput(page).fill(newReqId);
    await repoUrlInput(page).fill(newRepoUrl);
    await fileNamesInput(page).fill(newFileNames);

    const updated = page.waitForResponse(
      (res) => res.url().includes(PROJECT_API) && res.request().method() === 'PATCH'
    );
    await saveBtn(page).click();
    expect(await (await updated).json()).toMatchObject({ success: true });
    await expect(popup(page)).not.toHaveClass(/is-open/);

    await search(page, projectName);
    await expect(row.locator('td[aria-describedby="projectGrid_reqId"]')).toHaveText(newReqId);

    // 수정 결과가 DB 에 반영됐는지 상세 API 로 확인한다.
    const rowId = await row.getAttribute('id');
    const detail = await page.request.get(`${PROJECT_API}/${encodeURIComponent(rowId ?? '')}`);
    expect(await detail.json()).toMatchObject({
      success: true,
      project: { reqId: newReqId, repoUrl: newRepoUrl, fileNames: newFileNames },
    });

    expect(alerts).toEqual([]);
  });

  test('REPO동기화 버튼을 누르면 동기화 API 가 호출되고 결과 요약이 표시된다', async ({ page }) => {
    // 결과 건수는 계정의 기존 데이터·저장소 상태에 따라 달라지므로 호출 성공과 응답 shape 만 본다.
    const synced = page.waitForResponse(
      (res) => res.url().includes(`${PROJECT_API}/repo-sync`) && res.request().method() === 'POST',
      { timeout: 60_000 }
    );
    await page.locator('#btnRepoSync').click();

    const response = await synced;
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body).toMatchObject({ success: true });
    expect(body.result).toEqual(expect.objectContaining({
      lookbackDays: 30,
      total: expect.any(Number),
      updated: expect.any(Number),
      items: expect.any(Array),
    }));

    // 결과는 기본 alert() 가 아니라 공통 레이어 팝업(appDialog)으로 보여 준다.
    const dialog = page.locator('#appDialog');
    await expect(dialog).toHaveClass(/is-open/);
    await expect(page.locator('#appDialogTitle')).toHaveText('REPO동기화');
    await expect(page.locator('#appDialogMessage')).toHaveText(/REPO동기화 완료|동기화 대상 프로젝트가 없습니다/);
    // 실패 건이 있으면 경고 톤, 없으면 안내 톤
    await expect(dialog).toHaveClass(body.result.failed > 0 ? /is-warn/ : /is-info/);

    await page.locator('#appDialogConfirm').click();
    await expect(dialog).not.toHaveClass(/is-open/);
    await expect(page.locator('#btnRepoSync')).toBeEnabled();

    // 기본 대화상자는 한 번도 뜨지 않아야 한다.
    expect(alerts).toEqual([]);
  });

  test('프로젝트명 없이 저장하면 등록 팝업 위에 경고 레이어 팝업이 뜨고, 닫으면 프로젝트명으로 포커스가 간다', async ({ page }) => {
    await openCreatePopup(page);
    await nameInput(page).fill('');

    // 검증에서 막혀야 하므로 저장 요청이 나가면 안 된다.
    let posted = false;
    page.on('request', (req) => {
      if (req.url().includes(PROJECT_API) && req.method() === 'POST') posted = true;
    });
    await saveBtn(page).click();

    const dialog = page.locator('#appDialog');
    await expect(dialog).toHaveClass(/is-open/);
    await expect(dialog).toHaveClass(/is-warn/);
    await expect(page.locator('#appDialogMessage')).toHaveText('프로젝트명을 입력하세요.');

    // 경고 팝업이 등록 팝업보다 위에 있어 확인 버튼을 실제로 누를 수 있어야 한다.
    await page.locator('#appDialogConfirm').click();
    await expect(dialog).not.toHaveClass(/is-open/);

    // 등록 팝업은 그대로 열려 있고, 프로젝트명 입력칸으로 포커스가 돌아온다.
    await expect(popup(page)).toHaveClass(/is-open/);
    await expect(nameInput(page)).toBeFocused();
    expect(posted).toBe(false);
    expect(alerts).toEqual([]);
  });

  /** UI 로 프로젝트를 하나 만들고, 이름으로 조회해 그 행을 돌려준다. */
  async function createAndFind(page: Page, projectName: string) {
    await openCreatePopup(page);
    await nameInput(page).fill(projectName);
    const created = page.waitForResponse(
      (res) => res.url().includes(PROJECT_API) && res.request().method() === 'POST'
    );
    await saveBtn(page).click();
    expect(await (await created).json()).toMatchObject({ success: true });
    await expect(popup(page)).not.toHaveClass(/is-open/);
    await search(page, projectName);
    const row = gridRows(page).filter({ hasText: projectName });
    await expect(row).toHaveCount(1);
    return row;
  }

  test('삭제 버튼은 수정 팝업에서만 REPO PUSH 왼쪽에 빨간색으로 보인다', async ({ page }) => {
    const deleteBtn = page.locator('#btnPopupDelete');

    // 등록 모드에서는 숨김
    await openCreatePopup(page);
    await expect(deleteBtn).toBeHidden();
    await page.locator('#btnPopupCancel').click();

    const row = await createAndFind(page, `e2e Test 프로젝트 ${marker()}`);
    await row.locator('.btn-detail').click();
    await expect(page.locator('#projectPopupTitle')).toHaveText('프로젝트 수정');
    await expect(deleteBtn).toBeVisible();
    await expect(deleteBtn).toHaveText('삭제');

    // REPO PUSH 바로 왼쪽 — DOM 상 바로 앞 형제이고 화면에서도 저장 버튼보다 왼쪽에 있다.
    expect(
      await page.locator('#btnPopupRepoPush').evaluate((el) => el.previousElementSibling?.id)
    ).toBe('btnPopupDelete');
    const del = (await deleteBtn.boundingBox())!;
    const save = (await saveBtn(page).boundingBox())!;
    expect(del.x + del.width).toBeLessThanOrEqual(save.x);

    // 빨간색 (--destructive: hsl(0 72.2% 50.6%) ≈ rgb(220, 38, 38))
    const [r, g, b] = (await deleteBtn.evaluate((el) => getComputedStyle(el).backgroundColor))
      .match(/\d+/g)!.map(Number);
    expect(r).toBeGreaterThan(180);
    expect(g).toBeLessThan(80);
    expect(b).toBeLessThan(80);

    expect(alerts).toEqual([]);
  });

  test('REPO PUSH 버튼은 수정 팝업에서만 저장 버튼 바로 왼쪽에 보인다', async ({ page }) => {
    const pushBtn = page.locator('#btnPopupRepoPush');

    // 등록 모드에서는 숨김 — 저장 전에는 푸시할 DB 값이 없다
    await openCreatePopup(page);
    await expect(pushBtn).toBeHidden();
    await page.locator('#btnPopupCancel').click();

    const row = await createAndFind(page, `e2e Test 프로젝트 ${marker()}`);
    await row.locator('.btn-detail').click();
    await expect(page.locator('#projectPopupTitle')).toHaveText('프로젝트 수정');
    await expect(pushBtn).toBeVisible();
    await expect(pushBtn).toHaveText('REPO PUSH');

    // 저장 버튼 바로 왼쪽 — DOM 상 바로 앞 형제이고 화면에서도 왼쪽에 있다.
    expect(await saveBtn(page).evaluate((el) => el.previousElementSibling?.id)).toBe('btnPopupRepoPush');
    const push = (await pushBtn.boundingBox())!;
    const save = (await saveBtn(page).boundingBox())!;
    expect(push.x + push.width).toBeLessThanOrEqual(save.x);

    expect(alerts).toEqual([]);
  });

  /* 저장소 URL 이 없는 프로젝트로 돌린다 — 외부 GitHub 호출 없이 실패 경로를 확인할 수 있고,
     결과가 기본 alert() 가 아니라 공통 레이어 팝업으로 나오는지도 같이 본다. */
  test('저장소 URL 이 없으면 REPO PUSH 가 레이어 팝업으로 실패 사유를 알린다', async ({ page }) => {
    const row = await createAndFind(page, `e2e Test 프로젝트 ${marker()}`);
    await row.locator('.btn-detail').click();
    await expect(popup(page)).toHaveClass(/is-open/);

    const pushed = page.waitForResponse(
      (res) => res.url().includes('/repo-push') && res.request().method() === 'POST'
    );
    await page.locator('#btnPopupRepoPush').click();
    expect(await (await pushed).json()).toMatchObject({ success: false });

    const dialog = page.locator('#appDialog');
    await expect(dialog).toHaveClass(/is-open/);
    await expect(page.locator('#appDialogTitle')).toHaveText('REPO PUSH');
    await expect(page.locator('#appDialogMessage')).toHaveText(/저장소 URL/);

    await page.locator('#appDialogConfirm').click();
    await expect(dialog).not.toHaveClass(/is-open/);
    // 수정 팝업은 그대로 열려 있다 (푸시는 화면을 닫지 않는다)
    await expect(popup(page)).toHaveClass(/is-open/);
    await expect(page.locator('#btnPopupRepoPush')).toBeEnabled();

    expect(alerts).toEqual([]);
  });

  test('삭제 확인에서 아니오면 수정 팝업이 유지되고, 예면 삭제 후 팝업이 닫히고 목록이 재조회된다', async ({ page }) => {
    const projectName = `e2e Test 프로젝트 ${marker()}`;
    const row = await createAndFind(page, projectName);
    const projectId = await row.getAttribute('id');

    await row.locator('.btn-detail').click();
    await expect(popup(page)).toHaveClass(/is-open/);

    const dialog = page.locator('#appDialog');
    const yesBtn = page.locator('#appDialogConfirm');
    const noBtn = page.locator('#appDialogCancel');

    // ── 아니오: 확인 팝업만 닫히고 수정 팝업·데이터는 그대로
    let deleteSent = false;
    page.on('request', (req) => {
      if (req.method() === 'DELETE') deleteSent = true;
    });
    await page.locator('#btnPopupDelete').click();
    await expect(dialog).toHaveClass(/is-open/);
    await expect(page.locator('#appDialogTitle')).toHaveText('프로젝트 삭제');
    await expect(yesBtn).toHaveText('예');
    await expect(noBtn).toHaveText('아니오');

    await noBtn.click();
    await expect(dialog).not.toHaveClass(/is-open/);
    await expect(popup(page)).toHaveClass(/is-open/);
    await expect(page.locator('#projectPopupTitle')).toHaveText('프로젝트 수정');
    expect(deleteSent).toBe(false);

    // ── 예: DELETE → 수정 팝업 닫힘 → 목록 재조회(해당 행 사라짐)
    await page.locator('#btnPopupDelete').click();
    await expect(dialog).toHaveClass(/is-open/);

    const deleted = page.waitForResponse(
      (res) => res.url().includes(`${PROJECT_API}/`) && res.request().method() === 'DELETE'
    );
    const reloaded = page.waitForResponse(
      (res) => res.url().includes(PROJECT_API) && res.request().method() === 'GET'
    );
    await yesBtn.click();

    expect(await (await deleted).json()).toMatchObject({ success: true, projectId });
    await reloaded;
    await expect(dialog).not.toHaveClass(/is-open/);
    await expect(popup(page)).not.toHaveClass(/is-open/);
    await expect(gridRows(page).filter({ hasText: projectName })).toHaveCount(0);

    // DB 에서도 지워졌는지 단건 API 로 확인
    const detail = await page.request.get(`${PROJECT_API}/${encodeURIComponent(projectId ?? '')}`);
    expect(await detail.json()).toMatchObject({ success: false });

    expect(alerts).toEqual([]);
  });
});
