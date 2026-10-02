/**
 * /user/workflow/step1~3 · ends 화면 테스트.
 * 셀렉터는 step1/2/3.jsp · ends.jsp 와 static/js/workflow.js 가 만드는 실제 id/class 를 그대로 쓴다.
 *
 *   #newTaskName                   태스크이름 입력 (스텝1 화면에만 있다)
 *   #btnCreateTask                 태스크생성 버튼 (스텝1 화면에만 있다)
 *   #btnRefresh                    새로고침 버튼
 *   #workflowGrid tr.jqgrow        진행 중 태스크 행 (rowId = Flowable 태스크 id)
 *   td[aria-describedby="workflowGrid_taskId"]    태스크 ID 셀 (WF-...)
 *   td[aria-describedby="workflowGrid_taskName"]  태스크이름 셀 (task_master)
 *   .btn-complete                  행의 완료 버튼
 *   #load_workflowGrid             jqGrid 로딩 오버레이
 *   #finishedGrid tr.jqgrow        종료된 태스크 행 (종료 화면)
 *   td[aria-describedby="finishedGrid_stepName"]  종료 목록의 상태 셀
 *   .wf-steps__item.is-current     현재 단계 표시
 *   #navWorkflowGroup / #navWorkflowToggle / #navWorkflowSubmenu  좌측 워크플로우 메뉴
 *
 *   #appDialog(.is-open/.is-warn)  공통 알림·확인 레이어 팝업 (views/common/dialog.jsp)
 *   #appDialogMessage / #appDialogConfirm / #appDialogCancel
 *
 * 이 화면들은 브라우저 기본 alert()/confirm() 을 쓰지 않는다. 기본 대화상자가 뜨면
 * nativeDialogs 에 쌓여 테스트가 실패한다.
 *
 * 이 테스트는 만든 태스크를 지우지 않는다(다른 스펙과 같은 정책 — e2e/README.md).
 * 수명주기 테스트가 만든 건은 종료까지 흘려보내고, 거부·취소 테스트가 만든 건만 남는다.
 */
import { test, expect, Page } from '@playwright/test';

const STEP_PATH = {
  step1: '/user/workflow/step1',
  step2: '/user/workflow/step2',
  step3: '/user/workflow/step3',
} as const;

type StepKey = keyof typeof STEP_PATH;

/** 종료 화면 — BPMN 의 단계가 아니라 끝난 프로세스를 조회하는 화면이다. */
const ENDS_PATH = '/user/workflow/ends';

/** 목록/생성 API (GET 목록, POST 생성이 같은 URL) */
const TASKS_API = '/user/workflow/tasks';
/** 종료 목록 API */
const FINISHED_API = '/user/workflow/finished';

/** 태스크 ID 형식 — WorkflowService.generateTaskId() */
const TASK_ID_PATTERN = /^WF-\d{8}-\d{6}-[0-9A-F]{4}$/;

const grid = (p: Page) => p.locator('#workflowGrid tr.jqgrow');
const finishedRows = (p: Page) => p.locator('#finishedGrid tr.jqgrow');
const rowOf = (p: Page, taskId: string) => grid(p).filter({ hasText: taskId });

/** jqGrid 가 각 td 에 붙이는 aria-describedby = <gridId>_<colName> */
const TASK_NAME_CELL = 'td[aria-describedby="workflowGrid_taskName"]';
const FINISHED_NAME_CELL = 'td[aria-describedby="finishedGrid_taskName"]';
const FINISHED_STEP_CELL = 'td[aria-describedby="finishedGrid_stepName"]';

const dialog = (p: Page) => p.locator('#appDialog');
const dialogMessage = (p: Page) => p.locator('#appDialogMessage');
const dialogConfirm = (p: Page) => p.locator('#appDialogConfirm');
const dialogCancel = (p: Page) => p.locator('#appDialogCancel');

/** 테스트가 만든 데이터를 지우지 않으므로 고유 마커를 붙여 쌓는다 (e2e/README.md). */
const marker = () => `e2e-${Date.now()}`;

/** 팝업이 열려 특정 문구를 보여 줄 때까지 기다린다. */
async function expectDialog(page: Page, text: string | RegExp) {
  await expect(dialog(page)).toHaveClass(/is-open/);
  await expect(dialogMessage(page)).toContainText(text);
}

/** 확인 버튼으로 팝업을 닫는다(알림은 확인 하나뿐이다). */
async function closeDialog(page: Page) {
  await dialogConfirm(page).click();
  await expect(dialog(page)).not.toHaveClass(/is-open/);
}

/** 단계 화면을 열고 목록 조회가 끝날 때까지 기다린다. */
async function openStep(page: Page, step: StepKey) {
  const listed = page.waitForResponse(
    (res) => res.url().includes(TASKS_API) && res.request().method() === 'GET'
  );
  await page.goto(STEP_PATH[step]);
  await listed;
  await expect(page.locator('#load_workflowGrid')).toBeHidden();
}

/** 종료 화면을 열고 목록 조회가 끝날 때까지 기다린다. */
async function openEnds(page: Page) {
  const listed = page.waitForResponse((res) => res.url().includes(FINISHED_API));
  await page.goto(ENDS_PATH);
  await listed;
  await expect(page.locator('#load_finishedGrid')).toBeHidden();
}

/**
 * 태스크생성 — task_master 에 이름이 남고 프로세스가 시작된다.
 * 결과 안내 팝업을 닫은 뒤 서버가 발급한 태스크 ID 를 돌려준다.
 */
async function createTask(page: Page, taskName: string): Promise<string> {
  const created = page.waitForResponse(
    (res) => res.url().includes(TASKS_API) && res.request().method() === 'POST'
  );
  const reloaded = page.waitForResponse(
    (res) => res.url().includes(TASKS_API) && res.request().method() === 'GET'
  );

  await page.locator('#newTaskName').fill(taskName);
  await page.locator('#btnCreateTask').click();

  const response = await created;
  expect(response.status()).toBe(200);

  const body = await response.json();
  expect(body).toMatchObject({ success: true, taskName });
  expect(body.taskId).toMatch(TASK_ID_PATTERN);

  // 생성 결과는 레이어 팝업으로 안내된다.
  await expectDialog(page, body.taskId);
  await closeDialog(page);

  await reloaded;
  await expect(page.locator('#load_workflowGrid')).toBeHidden();

  // 입력칸은 비워져 다음 생성을 받을 준비가 된다.
  await expect(page.locator('#newTaskName')).toHaveValue('');

  return body.taskId as string;
}

/** 완료 버튼 → 확인 팝업에서 승인 → 결과 팝업 닫기. 서버 응답(JSON)을 돌려준다. */
async function completeTask(page: Page, taskId: string) {
  await rowOf(page, taskId).locator('.btn-complete').click();

  // 먼저 확인을 받는다(기본 confirm 이 아니라 팝업).
  await expectDialog(page, '완료하고 다음 단계로');

  const completed = page.waitForResponse(
    (res) => /\/tasks\/[^/]+\/complete$/.test(res.url()) && res.request().method() === 'POST'
  );
  await dialogConfirm(page).click();

  const response = await completed;
  expect(response.status()).toBe(200);
  const body = await response.json();

  // 결과도 팝업으로 안내된다.
  await expectDialog(page, body.finished ? '태스크가 종료되었습니다' : `다음 단계: ${body.nextStep}`);
  await closeDialog(page);

  return body;
}

/** 현재 페이지의 CSRF 헤더 — REST 를 화면 없이 직접 호출할 때 쓴다. */
async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const token = await page.locator('meta[name="_csrf"]').getAttribute('content');
  const header = await page.locator('meta[name="_csrf_header"]').getAttribute('content');
  expect(token).toBeTruthy();
  expect(header).toBeTruthy();

  return { [header!]: token!, 'X-Requested-With': 'XMLHttpRequest' };
}

test.describe('워크플로우 화면', () => {
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

  test('좌측 메뉴에 워크플로우와 하위 화면 4개가 있다', async ({ page }) => {
    await page.goto('/user/dashboard');

    await expect(page.locator('#navWorkflowToggle .nav-label')).toHaveText('워크플로우');
    await expect(page.locator('#navWorkflowSubmenu .nav-submenu__item')).toHaveCount(4);
    await expect(page.locator('#navWorkflowSubmenu .nav-submenu__item a')).toHaveText([
      '스텝1',
      '스텝2',
      '스텝3',
      '종료',
    ]);

    // 워크플로우 화면에서는 그룹이 펼쳐진 채로 열리고, 해당 하위 메뉴가 활성이다.
    await page.goto(STEP_PATH.step2);
    await expect(page.locator('#navWorkflowGroup')).toHaveClass(/is-open/);
    await expect(page.locator('#navWorkflowSubmenu .nav-submenu__item.active a')).toHaveText(
      '스텝2'
    );
  });

  test('스텝1 화면이 열리고 태스크이름 입력과 생성 버튼이 보인다', async ({ page }) => {
    await openStep(page, 'step1');

    await expect(page.locator('.page-header__title')).toHaveText('스텝1');
    await expect(page.locator('#workflowGrid')).toBeVisible();
    await expect(page.locator('#newTaskName')).toBeEditable();
    await expect(page.locator('#btnCreateTask')).toBeEnabled();
    await expect(page.locator('.wf-steps__item.is-current')).toHaveText('스텝1');

    // 대화상자는 화면에 들어 있고 닫힌 상태여야 한다.
    await expect(dialog(page)).toHaveCount(1);
    await expect(dialog(page)).not.toHaveClass(/is-open/);

    // 상단바에 로그인 사용자 이름이 채워져 있어야 한다.
    await expect(page.locator('.topbar__username')).not.toBeEmpty();
  });

  test('태스크생성은 스텝1 화면에만 있다', async ({ page }) => {
    await openStep(page, 'step2');
    await expect(page.locator('.wf-steps__item.is-current')).toHaveText('스텝2');
    await expect(page.locator('#btnCreateTask')).toHaveCount(0);
    await expect(page.locator('#newTaskName')).toHaveCount(0);

    await openStep(page, 'step3');
    await expect(page.locator('.wf-steps__item.is-current')).toHaveText('스텝3');
    await expect(page.locator('#btnCreateTask')).toHaveCount(0);
    // 종료된 태스크 목록은 스텝 화면이 아니라 종료 화면에 있다.
    await expect(page.locator('#finishedGrid')).toHaveCount(0);
  });

  test('종료 화면은 종료된 태스크만 읽기 전용으로 보여 준다', async ({ page }) => {
    await openEnds(page);

    await expect(page.locator('.page-header__title')).toHaveText('종료');
    await expect(page.locator('.wf-steps__item.is-current')).toHaveText('종료');
    await expect(page.locator('#finishedGrid')).toBeVisible();

    // 진행 중 목록도, 생성·완료 버튼도 없다.
    await expect(page.locator('#workflowGrid')).toHaveCount(0);
    await expect(page.locator('#btnCreateTask')).toHaveCount(0);
    await expect(page.locator('.btn-complete')).toHaveCount(0);

    // 조회된 행이 있다면 상태가 전부 "종료" 여야 한다.
    // 다중 요소 locator 에 단일 기대값을 주면 strict mode 위반이므로 값을 모아서 비교한다.
    const steps = (await finishedRows(page).locator(FINISHED_STEP_CELL).allInnerTexts()).map((t) =>
      t.trim()
    );
    if (steps.length > 0) {
      expect([...new Set(steps)]).toEqual(['종료']);
    }
  });

  test('태스크 이름 없이 생성하면 경고 팝업이 뜨고 전송되지 않는다', async ({ page }) => {
    await openStep(page, 'step1');

    let posted = false;
    page.on('request', (req) => {
      if (req.url().includes(TASKS_API) && req.method() === 'POST') posted = true;
    });

    await page.locator('#newTaskName').fill('   ');
    await page.locator('#btnCreateTask').click();

    // 기본 alert 가 아니라 경고 톤의 레이어 팝업이다.
    await expectDialog(page, '태스크 이름을 입력하세요.');
    await expect(dialog(page)).toHaveClass(/is-warn/);
    await expect(page.locator('#appDialogTitle')).toHaveText('경고');
    // 알림에는 취소 버튼이 없다.
    await expect(dialogCancel(page)).toBeHidden();

    await closeDialog(page);
    // 닫으면 입력칸으로 초점이 돌아온다.
    await expect(page.locator('#newTaskName')).toBeFocused();

    expect(posted).toBe(false);
  });

  test('완료 확인 팝업에서 취소하면 단계가 넘어가지 않는다', async ({ page }) => {
    await openStep(page, 'step1');
    const taskId = await createTask(page, `취소 확인 ${marker()}`);

    let posted = false;
    page.on('request', (req) => {
      if (/\/complete$/.test(req.url()) && req.method() === 'POST') posted = true;
    });

    await rowOf(page, taskId).locator('.btn-complete').click();
    await expectDialog(page, '완료하고 다음 단계로');
    // 확인 팝업에는 취소 버튼이 있다.
    await expect(dialogCancel(page)).toBeVisible();

    await dialogCancel(page).click();
    await expect(dialog(page)).not.toHaveClass(/is-open/);

    expect(posted).toBe(false);
    await expect(rowOf(page, taskId)).toHaveCount(1);
    await expect(rowOf(page, taskId)).toContainText('스텝1');
  });

  test('태스크를 생성하면 이름과 함께 스텝1 목록에 추가된다', async ({ page }) => {
    await openStep(page, 'step1');

    const taskName = `결재 요청 ${marker()}`;
    const taskId = await createTask(page, taskName);

    /* 목록은 생성 최신순이라 새 태스크가 1페이지 맨 위에 온다.
       (행 수로는 확인하지 않는다 — 페이지 크기가 10 이라 누적되면 늘지 않는다) */
    await expect(grid(page).first()).toContainText(taskId);

    const row = rowOf(page, taskId);
    await expect(row).toHaveCount(1);
    await expect(row.locator(TASK_NAME_CELL)).toHaveText(taskName);
    await expect(row).toContainText('스텝1');
    await expect(row.locator('.btn-complete')).toBeVisible();
  });

  test('태스크 이름이 스텝2 · 스텝3 · 종료 화면까지 따라간다', async ({ page }) => {
    await openStep(page, 'step1');
    const taskName = `승인 건 ${marker()}`;
    const taskId = await createTask(page, taskName);

    // 스텝1 완료 → 스텝2
    expect(await completeTask(page, taskId)).toMatchObject({
      success: true,
      nextStep: '스텝2',
      finished: false,
    });
    await expect(rowOf(page, taskId)).toHaveCount(0);

    // 스텝2 화면에 이름과 함께 나타나고, 완료하면 스텝3 으로
    await openStep(page, 'step2');
    await expect(rowOf(page, taskId).locator(TASK_NAME_CELL)).toHaveText(taskName);
    expect(await completeTask(page, taskId)).toMatchObject({
      success: true,
      nextStep: '스텝3',
      finished: false,
    });

    // 스텝3 화면에 이름과 함께 나타나고, 완료하면 종료
    await openStep(page, 'step3');
    await expect(rowOf(page, taskId).locator(TASK_NAME_CELL)).toHaveText(taskName);
    expect(await completeTask(page, taskId)).toMatchObject({
      success: true,
      nextStep: '종료',
      finished: true,
    });

    // 스텝3 목록에서 빠지고, 종료 화면에서 이름과 함께 조회된다.
    await expect(rowOf(page, taskId)).toHaveCount(0);

    await openEnds(page);
    const endedRow = finishedRows(page).filter({ hasText: taskId });
    await expect(endedRow).toHaveCount(1);
    await expect(endedRow.locator(FINISHED_NAME_CELL)).toHaveText(taskName);
    await expect(endedRow).toContainText('종료');
  });

  test('현재 단계와 다른 단계로 온 완료 요청은 거부된다', async ({ page }) => {
    // 스텝2 에 올려둔 뒤, 스텝1 화면인 척 완료를 요청한다.
    await openStep(page, 'step1');
    const taskId = await createTask(page, `반려 확인 ${marker()}`);

    const staleFlowableTaskId = await rowOf(page, taskId).getAttribute('id');
    expect(staleFlowableTaskId).toBeTruthy();

    await completeTask(page, taskId);

    await openStep(page, 'step2');
    const headers = await csrfHeaders(page);
    const liveFlowableTaskId = await rowOf(page, taskId).getAttribute('id');

    // ① 단계가 어긋난 요청 — 현재 단계(스텝2)를 알려주며 거부한다.
    const mismatched = await page.request.post(`${TASKS_API}/${liveFlowableTaskId}/complete`, {
      headers,
      data: { step: 'step1' },
    });
    expect(mismatched.status()).toBe(200);
    const mismatchedBody = await mismatched.json();
    expect(mismatchedBody).toMatchObject({ success: false });
    expect(mismatchedBody.message).toMatch(/스텝2/);

    // ② 이미 완료돼 사라진 엔진 태스크 id — 대상 없음으로 거부한다.
    const stale = await page.request.post(`${TASKS_API}/${staleFlowableTaskId}/complete`, {
      headers,
      data: { step: 'step1' },
    });
    expect(await stale.json()).toMatchObject({ success: false });

    // 거부돼도 태스크는 스텝2 에 그대로 남아 있어야 한다.
    await openStep(page, 'step2');
    await expect(rowOf(page, taskId)).toHaveCount(1);
  });

  test('목록은 jqGrid 계약 모양으로 내려온다', async ({ page }) => {
    const listed = page.waitForResponse(
      (res) => res.url().includes(TASKS_API) && res.request().method() === 'GET'
    );
    await page.goto(STEP_PATH.step1);
    const body = await (await listed).json();

    expect(body).toMatchObject({
      page: expect.any(Number),
      total: expect.any(Number),
      records: expect.any(Number),
      rows: expect.any(Array),
    });

    // 행에는 화면이 쓰는 두 가지 id 와 이름이 모두 실려 있어야 한다.
    if (body.rows.length > 0) {
      expect(body.rows[0]).toMatchObject({
        taskId: expect.stringMatching(TASK_ID_PATTERN),
        flowableTaskId: expect.any(String),
        stepName: expect.any(String),
      });
      expect(body.rows[0]).toHaveProperty('taskName');
    }

    // 종료 목록도 같은 모양이어야 화면이 같은 그리드 코드를 쓴다.
    const finished = page.waitForResponse((res) => res.url().includes(FINISHED_API));
    await page.goto(ENDS_PATH);
    expect(await (await finished).json()).toMatchObject({
      page: expect.any(Number),
      rows: expect.any(Array),
    });
  });
});
