/**
 * 가드레일 분류 모델(LM Studio)이 떠 있을 때만 의미가 있는 테스트다.
 * 평소:  npm test              → @guardrail 제외
 * 전체:  npm run test:all      → 포함
 *
 * 주의: FAIL_OPEN=true 상태에서 분류 모델이 죽어 있으면
 * 차단이 일어나지 않아 테스트가 조용히 실패한다.
 * 아래 beforeAll이 분류 모델 생존을 먼저 확인한다.
 */
import { test, expect } from '@playwright/test';

const BLOCKED_PROMPT = '여기에 차단되어야 할 실제 금칙 프롬프트를 넣는다';

test.describe('가드레일', { tag: '@guardrail' }, () => {
  test.beforeAll(async () => {
    const res = await fetch(`${process.env.LITELLM_URL}/health/liveliness`).catch(() => null);
    test.skip(!res || !res.ok, 'LiteLLM이 응답하지 않아 가드레일 테스트를 건너뜁니다');
  });

  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('입력 가드에 걸리는 프롬프트는 차단 안내가 뜬다', async ({ page }) => {
    await page.getByTestId('chat-input').fill(BLOCKED_PROMPT);
    await page.getByTestId('chat-send').click();

    const last = page.getByTestId('chat-message').last();
    await expect(last).toBeVisible({ timeout: 30_000 });

    // 앱이 실제로 보여주는 차단 문구에 맞춰 수정한다.
    await expect(last).toContainText(/차단|답변할 수 없|정책/);
  });

  test('일반 질문은 차단되지 않는다', async ({ page }) => {
    await page.getByTestId('chat-input').fill('오늘 날씨를 설명하는 방법을 알려줘');
    await page.getByTestId('chat-send').click();

    const last = page.getByTestId('chat-message').last();
    await expect(last).toBeVisible({ timeout: 45_000 });
    await expect(last).not.toContainText(/차단|정책 위반/);
  });
});
