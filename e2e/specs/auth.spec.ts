/**
 * 실제 구조:
 *   http://mytechtest.jihun.com/  → 비로그인이면 "Keycloak으로 로그인합니다" 버튼이 있는 공개 홈
 *   버튼 클릭                      → Keycloak 로그인 페이지
 *   로그인 성공                    → 앱으로 복귀
 *
 * 버튼 셀렉터는 텍스트로 잡고 있다. 화면 문구가 바뀌면 loginButton()을 고칠 것.
 * 안정성을 위해서는 버튼에 data-testid="login-link" 를 붙이는 편이 낫다.
 */
import { test, expect, Page, Browser } from '@playwright/test';

const appHost = new URL(process.env.APP_URL!).hostname;

function loginButton(page: Page) {
  // 텍스트로 잡으면 버튼을 감싼 div까지 매칭돼 클릭이 빗나간다.
  // 링크/버튼 등 실제로 누를 수 있는 요소만 후보로 둔다.
  return page
    .getByTestId('login-link')
    .or(page.locator('a[href*="/oauth2/authorization/"]'))
    .or(page.getByRole('link', { name: /Keycloak/i }))
    .or(page.getByRole('button', { name: /Keycloak/i }))
    .first();
}

/** 저장된 세션을 쓰지 않는 깨끗한 컨텍스트 */
async function anonymousPage(browser: Browser) {
  const context = await browser.newContext({ storageState: undefined });
  return { context, page: await context.newPage() };
}

test.describe('인증 흐름', () => {
  test('비로그인 홈에는 로그인 버튼이 보인다', async ({ browser }) => {
    const { context, page } = await anonymousPage(browser);

    await page.goto('/');

    // 홈은 공개 페이지이므로 앱 도메인에 그대로 머물러야 한다.
    expect(new URL(page.url()).hostname).toBe(appHost);
    await expect(loginButton(page)).toBeVisible();

    await context.close();
  });

  test('로그인 버튼을 누르면 Keycloak 로그인 폼이 나온다', async ({ browser }) => {
    const { context, page } = await anonymousPage(browser);

    await page.goto('/');
    await loginButton(page).click();

    await expect(page.locator('#username')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('#kc-login')).toBeVisible();

    await context.close();
  });

  test('앱이 만드는 redirect_uri에 localhost가 섞이지 않는다', async ({ browser }) => {
    const { context, page } = await anonymousPage(browser);

    await page.goto('/');
    await loginButton(page).click();
    await page.locator('#username').waitFor({ state: 'visible', timeout: 20_000 });

    const redirectUri = new URL(page.url()).searchParams.get('redirect_uri') ?? '';
    expect(redirectUri, 'Keycloak 인가 요청에 redirect_uri가 없다').not.toBe('');
    expect(redirectUri).toContain(appHost);
    expect(redirectUri).not.toContain('localhost');

    await context.close();
  });

  test('아이디와 비밀번호를 넣으면 앱으로 돌아온다', async ({ browser }) => {
    const { context, page } = await anonymousPage(browser);

    await page.goto('/');
    await loginButton(page).click();
    await page.locator('#username').fill(process.env.E2E_USER!);
    await page.locator('#password').fill(process.env.E2E_PASSWORD!);
    await page.locator('#kc-login').click();

    await page.waitForURL(new RegExp(appHost), { timeout: 20_000 });
    await expect(loginButton(page)).toBeHidden();

    await context.close();
  });

  test('저장된 세션으로 접근하면 로그인 버튼이 없다', async ({ page }) => {
    await page.goto('/');

    expect(new URL(page.url()).hostname).toBe(appHost);
    await expect(loginButton(page)).toBeHidden();
  });
});
