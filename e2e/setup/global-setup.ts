import { chromium, FullConfig } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { preflight } from './preflight';
import { STORAGE_STATE } from '../playwright.config';

export default async function globalSetup(_config: FullConfig) {
  console.log('\n[1/2] 인프라 확인');
  await preflight();

  console.log('\n[2/2] Keycloak 로그인');

  const appUrl = process.env.APP_URL!;
  const keycloakHost = new URL(process.env.KEYCLOAK_URL!).hostname;

  fs.mkdirSync(path.dirname(STORAGE_STATE), { recursive: true });

  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    // Spring Security OAuth2 Login의 인가 요청 진입점으로 직접 이동한다.
    // 홈이 공개 페이지여도 여기로 가면 반드시 Keycloak으로 리다이렉트된다.
    const loginStart = process.env.LOGIN_START_PATH ?? '/oauth2/authorization/keycloak';
    const res = await page.goto(`${appUrl}${loginStart}`, { waitUntil: 'domcontentloaded' });

    if (res && res.status() === 404) {
      throw new Error(
        [
          `${loginStart} 가 404다.`,
          'application.yml의 spring.security.oauth2.client.registration 아래',
          '실제 등록 이름을 확인하고 .env.e2e의 LOGIN_START_PATH를 맞출 것.',
          '(예: registration.keycloak → /oauth2/authorization/keycloak)',
        ].join('\n')
      );
    }

    if (!page.url().includes(keycloakHost)) {
      throw new Error(
        [
          `${loginStart} 로 갔는데 Keycloak으로 넘어가지 않았다.`,
          `  도착한 곳: ${page.url()}`,
          'spring-boot-starter-oauth2-client 의존성과 SecurityConfig의',
          'oauth2Login() 설정이 실제로 적용되어 있는지 확인할 것.',
        ].join('\n')
      );
    }

    await page.locator('#username').fill(process.env.E2E_USER!);
    await page.locator('#password').fill(process.env.E2E_PASSWORD!);
    await page.locator('#kc-login').click();

    // 로그인 후 앱 도메인으로 되돌아오는 것까지 확인한다.
    await page.waitForURL(new RegExp(new URL(appUrl).hostname), { timeout: 20_000 });

    await context.storageState({ path: STORAGE_STATE });
    console.log(`  ✓ 세션 저장 완료 (${path.relative(process.cwd(), STORAGE_STATE)})\n`);
  } catch (e) {
    const shot = path.resolve(__dirname, '../.auth/login-failure.png');
    await page.screenshot({ path: shot, fullPage: true }).catch(() => {});
    throw new Error(
      [
        '',
        'Keycloak 로그인에 실패했습니다.',
        `  현재 URL: ${page.url()}`,
        `  스크린샷: ${shot}`,
        '',
        '자주 겪는 원인:',
        '  · redirect_uri 불일치 — Keycloak 클라이언트 설정에',
        `    ${appUrl}/* 가 등록되어 있는지 확인`,
        '  · 앱이 리다이렉트 주소를 localhost:8080으로 만들고 있음',
        '    → nginx의 X-Forwarded-* 헤더와 스프링의',
        '      server.forward-headers-strategy 설정 확인',
        '  · 테스트 계정 없음 또는 비밀번호 초기 변경 요구 상태',
        '',
        e instanceof Error ? e.message : String(e),
      ].join('\n')
    );
  } finally {
    await browser.close();
  }
}
