# E2E 테스트

브라우저로 앱을 실제로 조작해 흐름이 끝까지 붙어 있는지 확인한다.
앱 코드와 의존성이 없는 별도 npm 프로젝트이며, Gradle 빌드에 포함되지 않는다.

## 최초 1회

```bash
cd e2e
npm install
npm run install:browser
cp .env.e2e .env.e2e.local   # 필요하면 계정만 개인 값으로
```

`/etc/hosts` 에 도메인이 등록되어 있어야 한다.

```
127.0.0.1  mytechtest.jihun.com
127.0.0.1  keycloak.jihun.com
127.0.0.1  litellm.jihun.com
```

Keycloak 클라이언트 설정에 아래가 등록되어 있어야 한다.

- Valid redirect URIs: `http://mytechtest.jihun.com/*`
- Web origins: `http://mytechtest.jihun.com`

테스트 전용 계정을 하나 만들고 `Temporary password` 를 꺼둔다.
켜져 있으면 첫 로그인에서 비밀번호 변경 화면이 떠 자동화가 멈춘다.

## 매번

수동으로 띄우는 것들 (아침에 한 번):

```bash
docker start pgvector
cd ~/DockerFiles/keycloak && docker compose up -d
cd ~/DockerFiles/litellm && docker compose up -d
nginx
# IntelliJ에서 스프링부트 앱 실행
```

테스트:

```bash
cd e2e
npm test          # 가드레일 제외
npm run test:all  # 전체 (LM Studio 필요)
npm run test:ui   # 브라우저에서 단계별로 보기
npm run report    # 마지막 실행 리포트
```

기동되지 않은 구성요소가 있으면 preflight가 이름과 실행 명령을 찍고 멈춘다.

## 자주 막히는 곳

**redirect_uri에 localhost:8080이 들어간다**

nginx 뒤에 있으면 스프링이 원래 도메인을 모른다. nginx vhost에 헤더를 넘기고,

```nginx
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Port $server_port;
```

`application.yml` 에 아래를 넣는다.

```yaml
server:
  forward-headers-strategy: framework
```

`auth.spec.ts` 의 세 번째 테스트가 이걸 잡아준다.

**Keycloak이 HTTPS를 요구한다**

start-dev 모드라 보통 문제없지만, 프록시 뒤에서는 컨테이너 환경변수에
`KC_PROXY_HEADERS=xforwarded`, `KC_HOSTNAME_STRICT=false` 를 확인한다.

**actuator health가 로그인 페이지로 튕긴다**

Keycloak OAuth2 Login이 붙어 있으면 `/actuator/**` 도 보호 대상이 된다.
SecurityConfig에서 열어준다.

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/health/**").permitAll()
    .anyRequest().authenticated());
```

세부 정보를 보려면 `application.yml` 에도 아래가 필요하다.

```yaml
management:
  endpoints.web.exposure.include: health
  endpoint.health.show-details: always
```

nginx 도메인으로 외부에 노출되므로 `include` 는 health만 열어두는 게 안전하다.

**셀렉터를 못 찾는다**

`chat.spec.ts` 상단 주석의 `data-testid` 를 화면에 추가하거나,
아래로 실제 셀렉터를 찾아 고친다.

```bash
npx playwright codegen http://mytechtest.jihun.com
```

**개발 DB가 오염된다**

테스트는 데이터를 지우지 않고 고유 마커(`e2e-<타임스탬프>`)를 붙여 쌓는다.
쌓인 게 거슬리면 별도 DB(`ragchat_e2e`)를 만들고 `application-e2e.yml` 로
앱을 띄우는 방식으로 전환한다.

## 나중에 도커 컴포즈로 묶을 때

`preflight.ts` 의 체크 목록이 그대로 서비스 목록이 된다.
`playwright.config.ts` 에 `webServer` 블록을 추가해 컴포즈 기동을 맡기면 되고,
`specs/` 아래 테스트 코드는 바뀌지 않는다.
