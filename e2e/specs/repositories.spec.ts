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
 *   #appDialog(.is-open/.is-warn)  공통 알림 레이어 팝업 (#appDialogMessage / #appDialogConfirm)
 *
 * 이 화면은 기본 alert()/confirm() 을 쓰지 않는다. 기본 대화상자가 뜨면 nativeDialogs 에 쌓여 실패한다.
 *
 * '@github' 태그 테스트는 실제 GitHub API 를 호출한다(끌어오기 1회당 3회, 비인증 시 시간당 60회).
 * 오프라인·한도 소진 시: npx playwright test --grep-invert "@guardrail|@github"
 */
import { test, expect, Page } from '@playwright/test';

const REPOSITORIES_PATH = '/user/repositories';

/** 파일 목록 API */
const FILES_API = '/user/repository/files';

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
  });
});
