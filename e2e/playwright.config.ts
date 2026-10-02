import { defineConfig, devices } from '@playwright/test';
import dotenv from 'dotenv';
import path from 'path';

dotenv.config({ path: path.resolve(__dirname, '.env.e2e') });

export const STORAGE_STATE = path.resolve(__dirname, '.auth/storageState.json');

export default defineConfig({
  testDir: './specs',
  globalSetup: './setup/global-setup.ts',

  // 인프라와 DB를 공유하므로 병렬 실행은 하지 않는다.
  workers: 1,
  fullyParallel: false,

  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: process.env.APP_URL,
    storageState: STORAGE_STATE,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // LLM 응답은 느릴 수 있으므로 개별 액션 타임아웃을 넉넉히 잡는다.
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
