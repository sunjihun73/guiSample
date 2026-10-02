import net from 'net';
import dns from 'dns/promises';

type Check = {
  name: string;
  hint: string;
  run: () => Promise<void>;
};

const TIMEOUT_MS = 5_000;

async function httpAlive(url: string, accept: (status: number) => boolean = () => true) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      redirect: 'manual',
    });
    if (!accept(res.status)) {
      throw new Error(`예상치 못한 응답 코드: ${res.status}`);
    }
  } finally {
    clearTimeout(timer);
  }
}

async function tcpAlive(host: string, port: number) {
  await new Promise<void>((resolve, reject) => {
    const socket = new net.Socket();
    const fail = (e: Error) => {
      socket.destroy();
      reject(e);
    };
    socket.setTimeout(TIMEOUT_MS);
    socket.once('connect', () => {
      socket.end();
      resolve();
    });
    socket.once('timeout', () => fail(new Error('연결 타임아웃')));
    socket.once('error', fail);
    socket.connect(port, host);
  });
}

/**
 * actuator health를 읽어 어느 하위 구성요소가 DOWN인지까지 알려준다.
 * 세부 정보를 보려면 앱에 아래 설정이 필요하다.
 *   management.endpoint.health.show-details: always
 */
async function appHealth(url: string) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let res: Response;
  try {
    res = await fetch(url, { signal: controller.signal, redirect: 'manual' });
  } finally {
    clearTimeout(timer);
  }

  if (res.status === 302 || res.status === 401 || res.status === 403) {
    throw new Error(
      'actuator가 인증에 막혀 있다. SecurityConfig에서 /actuator/** 를 permitAll 처리할 것'
    );
  }

  const body = await res.text();
  let parsed: { status?: string; components?: Record<string, { status?: string }> };
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new Error(`health 응답이 JSON이 아니다 (status ${res.status})`);
  }

  if (parsed.status === 'UP') return;

  const down = Object.entries(parsed.components ?? {})
    .filter(([, v]) => v?.status && v.status !== 'UP')
    .map(([k, v]) => `${k}=${v.status}`);

  throw new Error(
    down.length > 0
      ? `앱 상태 ${parsed.status} — ${down.join(', ')}`
      : `앱 상태 ${parsed.status} (show-details 설정이 없으면 원인 확인 불가)`
  );
}

async function hostsEntry(domain: string) {
  try {
    await dns.lookup(domain);
  } catch {
    throw new Error(`${domain} 이름 해석 실패`);
  }
}

function buildChecks(): Check[] {
  const appUrl = process.env.APP_URL!;
  const keycloakUrl = process.env.KEYCLOAK_URL!;
  const litellmUrl = process.env.LITELLM_URL!;
  const realm = process.env.KEYCLOAK_REALM!;
  const healthPath = process.env.APP_HEALTH_PATH ?? '/';

  const appHost = new URL(appUrl).hostname;
  const keycloakHost = new URL(keycloakUrl).hostname;

  return [
    {
      name: 'hosts 파일 (앱 도메인)',
      hint: `sudo sh -c 'echo "127.0.0.1 ${appHost}" >> /etc/hosts'`,
      run: () => hostsEntry(appHost),
    },
    {
      name: 'hosts 파일 (Keycloak 도메인)',
      hint: `sudo sh -c 'echo "127.0.0.1 ${keycloakHost}" >> /etc/hosts'`,
      run: () => hostsEntry(keycloakHost),
    },
    {
      name: 'pgvector',
      hint: 'docker start pgvector',
      run: () => tcpAlive(process.env.PG_HOST!, Number(process.env.PG_PORT)),
    },
    {
      name: 'Keycloak',
      hint: 'cd ~/DockerFiles/keycloak && docker compose up -d',
      run: () => httpAlive(`${keycloakUrl}/realms/${realm}`, (s) => s === 200),
    },
    {
      name: 'LiteLLM',
      hint: 'cd ~/DockerFiles/litellm && docker compose up -d',
      run: () => httpAlive(`${litellmUrl}/health/liveliness`),
    },
    {
      name: 'nginx + 스프링부트 앱',
      hint: 'nginx 기동 후 IntelliJ에서 앱 실행 (프로파일 확인)',
      run: () => appHealth(`${appUrl}${healthPath}`),
    },
  ];
}

export async function preflight() {
  const checks = buildChecks();
  const failures: string[] = [];

  for (const check of checks) {
    try {
      await check.run();
      console.log(`  ✓ ${check.name}`);
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      console.log(`  ✗ ${check.name} — ${reason}`);
      failures.push(`  · ${check.name}\n      → ${check.hint}`);
    }
  }

  if (failures.length > 0) {
    throw new Error(
      [
        '',
        '기동되지 않은 구성요소가 있습니다. 아래를 먼저 실행하세요.',
        '',
        ...failures,
        '',
      ].join('\n')
    );
  }
}
