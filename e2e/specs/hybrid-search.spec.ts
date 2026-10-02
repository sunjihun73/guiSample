/**
 * 하이브리드 검색(BM25 + dense RRF) 도입 후 /user/rag 전송 경로 회귀 테스트.
 * 셀렉터는 chat.spec.ts 와 동일하게 rag.jsp 의 실제 id/class 를 쓴다.
 *
 *   #chatInput                          입력 textarea
 *   #chatSendBtn                        전송 버튼 (전송 중 disabled)
 *   #chatMessages .chat-msg             말풍선 행 (--user / --bot)
 *   #chatTyping                         응답 대기 표시 (첫 토큰 도착 시 제거)
 *   .chat-bubble.is-streaming           스트리밍 중인 답변 버블
 *   #ragSessionNewBtn                   새 채팅
 *   #ragSessionList .rag-session-item   세션 항목
 *
 * ⚠ 이 스펙이 "검증하지 못하는" 것 — 착각하지 말 것.
 *   application-e2e.yaml 은 chat.options.model 이 e2e-mock 이라 답변 텍스트가 목이다.
 *   따라서 "테오리아 청크가 실제로 검색됐는가" 는 여기서 단언할 수 없다.
 *   검색 품질의 항구적 회귀 테스트는 src/docs/measurements.md 의 SQL 단언이다.
 *   여기서 보는 것은 하이브리드 도입이 "전송 경로를 깨지 않았는가" 뿐이다.
 *   (소유권 격리 F.5 도 사용자 2명이 필요해 이 스위트 범위 밖이다.)
 */
import { test, expect, Page } from '@playwright/test';

const CHAT_PATH = process.env.CHAT_PATH ?? '/user/rag';

/** LLM 응답을 기다리는 테스트에 쓸 여유 시간 */
const ANSWER_TIMEOUT = 90_000;

const input = (p: Page) => p.locator('#chatInput');
const sendBtn = (p: Page) => p.locator('#chatSendBtn');
const messages = (p: Page) => p.locator('#chatMessages .chat-msg');
const botBubbles = (p: Page) => p.locator('#chatMessages .chat-msg--bot .chat-bubble');
const sessionItems = (p: Page) => p.locator('#ragSessionList .rag-session-item');

const marker = () => `e2e-${Date.now()}`;

/** 질문 전송 후 답변 스트리밍이 끝날 때까지 대기 (chat.spec.ts 와 동일 계약) */
async function askAndWait(page: Page, question: string) {
  await input(page).fill(question);
  await sendBtn(page).click();

  await expect(page.locator('#chatTyping')).toHaveCount(0, { timeout: ANSWER_TIMEOUT });
  await expect(page.locator('#chatMessages .chat-bubble.is-streaming')).toHaveCount(0, {
    timeout: ANSWER_TIMEOUT,
  });
  await expect(sendBtn(page)).toBeEnabled({ timeout: ANSWER_TIMEOUT });
}

async function startNewSession(page: Page) {
  await page.waitForLoadState('networkidle');

  const first = sessionItems(page).first();
  const beforeId = await first.getAttribute('data-session-id').catch(() => null);

  await page.locator('#ragSessionNewBtn').click();

  await expect
    .poll(() => first.getAttribute('data-session-id').catch(() => null), { timeout: 20_000 })
    .not.toBe(beforeId);

  await expect(first).toHaveClass(/is-selected/);
  await expect(messages(page)).toHaveCount(1);
}

test.describe('하이브리드 검색 회귀', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(CHAT_PATH);
    await expect(input(page)).toBeVisible();
  });

  test('고유명사 질문에도 SSE 스트림이 정상 종료된다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    // 하이브리드 도입의 동기가 된 질의 형태. 답변 내용이 아니라
    // BM25 경로가 끼어들어도 스트림이 깨지지 않는지를 본다.
    const question = `${marker()} 테오리아 행성은 어디에 있나`;

    // SSE 엔드포인트가 500 으로 죽지 않는지 직접 감시한다.
    // (소유 파일이 0건일 때의 in() jsonpath 크래시가 여기로 드러난다.)
    const failures: string[] = [];
    page.on('response', (res) => {
      if (res.url().includes('/user/rag/docs') && res.status() >= 400) {
        failures.push(`${res.status()} ${res.url()}`);
      }
    });

    await askAndWait(page, question);

    expect(failures, `/user/rag/docs 가 오류 응답을 냈다: ${failures.join(', ')}`).toEqual([]);

    // 인사 1 + 사용자 1 + 답변 1
    await expect(messages(page)).toHaveCount(3);

    const answer = (await botBubbles(page).last().innerText()).trim();
    expect(answer.length).toBeGreaterThan(1);
    expect(answer).not.toContain('응답을 받지 못했습니다');
    expect(answer).not.toContain('오류가 발생했습니다');
    await expect(input(page)).toHaveValue('');
  });

  test('카테고리를 한정해도 스트림이 정상 종료된다', async ({ page }) => {
    test.setTimeout(ANSWER_TIMEOUT + 30_000);
    await startNewSession(page);

    // category_id 와 user_name 이 함께 어드바이저 컨텍스트로 넘어가는 경로.
    // 카테고리가 하나도 없는 환경에서는 조용히 건너뛴다.
    const cats = page.locator('#ragCatList .rag-cat-btn[data-category-id]:not([data-category-id=""])');
    if ((await cats.count()) === 0) {
      test.skip(true, '등록된 카테고리가 없어 건너뛴다');
    }

    await cats.first().click();
    await expect(cats.first()).toHaveClass(/is-selected/);

    await askAndWait(page, `${marker()} 이 카테고리 문서 요약해줘`);

    await expect(messages(page)).toHaveCount(3);
    const answer = (await botBubbles(page).last().innerText()).trim();
    expect(answer).not.toContain('오류가 발생했습니다');
  });
});
