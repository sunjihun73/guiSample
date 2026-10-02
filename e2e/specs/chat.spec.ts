/**
 * /user/rag 화면 테스트. 셀렉터는 rag.jsp의 실제 id/class를 그대로 쓴다.
 *
 *   #chatInput                     입력 textarea
 *   #chatSendBtn                   전송 버튼 (전송 중 disabled)
 *   #chatMessages .chat-msg        말풍선 행 (--user / --bot)
 *   #chatTyping                    응답 대기 표시 (첫 토큰 도착 시 제거)
 *   .chat-bubble.is-streaming      스트리밍 중인 답변 버블
 *   #ragSessionNewBtn              새 채팅
 *   #ragSessionList .rag-session-item   세션 항목 (.is-selected가 활성)
 *   #ragCatList .rag-cat-btn       카테고리 버튼
 */
import { test, expect, Page } from '@playwright/test';

const CHAT_PATH = process.env.CHAT_PATH ?? '/user/rag';

/** LLM 응답을 기다리는 테스트에 쓸 여유 시간 */
const ANSWER_TIMEOUT = 90_000;

const input = (p: Page) => p.locator('#chatInput');
const sendBtn = (p: Page) => p.locator('#chatSendBtn');
const messages = (p: Page) => p.locator('#chatMessages .chat-msg');
const userMsgs = (p: Page) => p.locator('#chatMessages .chat-msg--user');
const botBubbles = (p: Page) => p.locator('#chatMessages .chat-msg--bot .chat-bubble');
const sessionItems = (p: Page) => p.locator('#ragSessionList .rag-session-item');

const marker = () => `e2e-${Date.now()}`;

/** 질문 전송 후 답변 스트리밍이 끝날 때까지 대기 */
async function askAndWait(page: Page, question: string) {
  await input(page).fill(question);
  await sendBtn(page).click();

  // 타이핑 표시가 사라지고 스트리밍 클래스가 걷힐 때까지가 한 턴의 끝이다.
  await expect(page.locator('#chatTyping')).toHaveCount(0, { timeout: ANSWER_TIMEOUT });
  await expect(page.locator('#chatMessages .chat-bubble.is-streaming')).toHaveCount(0, {
    timeout: ANSWER_TIMEOUT,
  });
  await expect(sendBtn(page)).toBeEnabled({ timeout: ANSWER_TIMEOUT });
}

/** 깨끗한 세션에서 시작 — 이전 테스트의 대화와 섞이지 않게 한다. */
async function startNewSession(page: Page) {
  // loadSessions()가 비동기로 목록을 채우므로, 그게 끝나기 전에 개수를 세면 안 된다.
  await page.waitForLoadState('networkidle');

  const first = sessionItems(page).first();
  const beforeId = await first.getAttribute('data-session-id').catch(() => null);

  await page.locator('#ragSessionNewBtn').click();

  // 목록 최상단이 새로 만든 세션으로 바뀔 때까지 기다린다.
  await expect
    .poll(() => first.getAttribute('data-session-id').catch(() => null), { timeout: 20_000 })
    .not.toBe(beforeId);

  await expect(first).toHaveClass(/is-selected/);
  // 새 채팅은 인사 1건만 남긴다.
  await expect(messages(page)).toHaveCount(1);
}

test.describe('RAG 채팅 화면', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(CHAT_PATH);
    await expect(input(page)).toBeVisible();
  });

  test('화면이 열리고 초기 인사 말풍선이 보인다', async ({ page }) => {
    await expect(page.locator('.page-header__title')).toHaveText('RAG 챗봇');
    await expect(botBubbles(page).first()).not.toBeEmpty();
    await expect(sendBtn(page)).toBeEnabled();

    // 상단바에 로그인 사용자 이름이 채워져 있어야 한다.
    await expect(page.locator('.topbar__username')).not.toBeEmpty();
  });

  test('카테고리는 기본적으로 "전체"가 선택되어 있다', async ({ page }) => {
    const selected = page.locator('#ragCatList .rag-cat-btn.is-selected');

    await expect(selected).toHaveCount(1);
    await expect(selected).toHaveAttribute('data-category-id', '');
    await expect(selected).toHaveAttribute('aria-pressed', 'true');
  });

  test('새 채팅을 누르면 세션이 추가되고 채팅창이 초기화된다', async ({ page }) => {
    await startNewSession(page);

    await expect(page.locator('#ragSessionMsg')).not.toHaveClass(/is-error/);
    // 새로 만든 세션은 기본 제목으로 목록 최상단에 놓인다.
    await expect(sessionItems(page).first().locator('.rag-session-item__title')).toHaveText(
      '새로운채팅'
    );
  });

  test('질문을 보내면 사용자 말풍선과 답변이 차례로 쌓인다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    const question = `${marker()} 이 시스템은 무엇을 하나요?`;
    await askAndWait(page, question);

    // 인사 1 + 사용자 1 + 답변 1
    await expect(messages(page)).toHaveCount(3);
    await expect(userMsgs(page).last()).toContainText(question);

    // 답변 내용은 검증하지 않는다. 비어 있지 않게 도착했는지만 본다.
    const answer = (await botBubbles(page).last().innerText()).trim();
    expect(answer.length).toBeGreaterThan(1);
    expect(answer).not.toContain('응답을 받지 못했습니다');
    expect(answer).not.toContain('오류가 발생했습니다');

    // 전송 후 입력창은 비워지고 다시 쓸 수 있어야 한다.
    await expect(input(page)).toHaveValue('');
  });

  test('전송 중에는 전송 버튼이 잠기고 끝나면 풀린다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    await input(page).fill(`${marker()} 조금 긴 설명을 부탁합니다`);
    await sendBtn(page).click();

    await expect(sendBtn(page)).toBeDisabled();
    await expect(sendBtn(page)).toBeEnabled({ timeout: ANSWER_TIMEOUT });
  });

  test('빈 입력은 전송되지 않는다', async ({ page }) => {
    // 페이지 로드 직후에는 loadSessions()가 비동기로 이전 대화를 복원하므로
    // 메시지 개수가 도중에 늘어난다. 새 세션에서 시작해 개수를 고정한다.
    await startNewSession(page);

    await input(page).fill('   ');
    await sendBtn(page).click();
    await page.waitForTimeout(1_000);

    await expect(messages(page)).toHaveCount(1);
    await expect(userMsgs(page)).toHaveCount(0);
  });

  test('Shift+Enter는 줄바꿈, Enter는 전송', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    await input(page).click();
    await page.keyboard.type('첫 줄');
    await page.keyboard.press('Shift+Enter');
    await page.keyboard.type('둘째 줄');

    // 아직 전송되지 않아야 한다.
    await expect(input(page)).toHaveValue(/첫 줄\n둘째 줄/);
    await expect(messages(page)).toHaveCount(1);

    await page.keyboard.press('Enter');

    await expect(userMsgs(page)).toHaveCount(1);
    await expect(input(page)).toHaveValue('');

    await expect(page.locator('#chatTyping')).toHaveCount(0, { timeout: ANSWER_TIMEOUT });
    await expect(sendBtn(page)).toBeEnabled({ timeout: ANSWER_TIMEOUT });
  });

  test('한 번의 Enter로 메시지가 두 번 전송되지 않는다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    await input(page).click();
    await page.keyboard.type(`${marker()} 중복 전송 확인`);
    await page.keyboard.press('Enter');

    await expect(page.locator('#chatTyping')).toHaveCount(0, { timeout: ANSWER_TIMEOUT });
    await expect(sendBtn(page)).toBeEnabled({ timeout: ANSWER_TIMEOUT });

    // 사용자 말풍선은 정확히 하나여야 한다 (IME Enter 중복 전송 회귀 방지).
    await expect(userMsgs(page)).toHaveCount(1);
  });

  test('새로고침해도 최신 세션의 대화가 복원된다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 60_000);
    await startNewSession(page);

    const question = `${marker()} 대화 복원 확인`;
    await askAndWait(page, question);

    await page.reload();

    // 페이지 로드 시 최신 세션이 자동 활성화되고 대화가 복원된다.
    await expect(sessionItems(page).first()).toHaveClass(/is-selected/, { timeout: 20_000 });
    await expect(userMsgs(page).filter({ hasText: question })).toHaveCount(1, {
      timeout: 20_000,
    });
  });

  test('다른 세션을 클릭하면 활성 세션이 바뀐다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 60_000);

    // 서로 다른 두 세션을 만들고 각각에 메시지를 남긴다.
    await startNewSession(page);
    const firstQuestion = `${marker()} 첫 세션`;
    await askAndWait(page, firstQuestion);

    await startNewSession(page);
    const secondQuestion = `${marker()} 둘째 세션`;
    await askAndWait(page, secondQuestion);

    // 두 번째(=목록 두 번째 항목)로 돌아가면 첫 세션 대화가 보여야 한다.
    await sessionItems(page).nth(1).click();

    await expect(sessionItems(page).nth(1)).toHaveClass(/is-selected/);
    await expect(userMsgs(page).filter({ hasText: firstQuestion })).toHaveCount(1, {
      timeout: 20_000,
    });
    await expect(userMsgs(page).filter({ hasText: secondQuestion })).toHaveCount(0);
  });
});
