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
 *   #popupProjectDescription_ifr   프로젝트 설명 - TinyMCE iframe (textarea를 대체)
 *   #btnPopupSave / #btnPopupCancel / #btnPopupClose  저장 / 취소 / 닫기
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

/** TinyMCE 본문 (contenteditable body) */
const descBody = (p: Page) =>
  p.frameLocator('#popupProjectDescription_ifr').locator('body#tinymce');

/** 테스트가 만든 데이터를 지우지 않으므로 고유 마커를 붙여 쌓는다 (e2e/README.md). */
const marker = () => `e2e-${Date.now()}`;

/**
 * alert 수집기. 저장/조회 실패 시 화면이 alert 로만 알리므로,
 * 실패를 타임아웃이 아니라 메시지로 드러나게 한다.
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
});
