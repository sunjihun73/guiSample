# 0003. Keycloak(OIDC) SSO 연동과 세션 관리

- 상태(Status): 승인됨(Accepted)
- 날짜(Date): 2026-08-19

## 배경(Context)

애플리케이션은 인증 없이 동작하다가 Keycloak SSO를 도입했다(커밋 `bba0f4d` "스프링 시큐리티 추가 및 키클럭 연동"). 그런데 인증 흐름의 상당 부분이 Spring Security의 **기본 동작(자동 구성)** 으로 채워져 있어 `SecurityConfig` 코드만 읽어서는 다음이 드러나지 않는다.

- 로그인 요청이 어떤 URL을 거쳐 Keycloak까지 갔다가 어디로 되돌아오는지
- **인증 후 세션이 만들어지는지, 만들어진다면 언제·어디에·무엇이 저장되는지**
- 토큰(access/refresh/ID)이 어디에 보관되며 만료 시 무슨 일이 일어나는지
- 앱 세션과 Keycloak SSO 세션이 각각 따로 존재한다는 사실과 그 결과
- **Keycloak 계정이 애플리케이션 DB(user_master)의 사용자 행과 언제 어떻게 연결되는지**

이 문서는 실행 중인 환경(Keycloak 26.7.0 컨테이너, 앱 `:8080`)에 실제 HTTP 요청을 보내 확인한 결과를 근거로 위 내용을 기록한다.

## 결정(Decision)

인증을 **Authorization Code + PKCE 기반 OIDC 로그인**으로 Keycloak에 위임하고, 로그인 이후의 상태는 **서버 측 HttpSession(JSESSIONID 쿠키)** 으로 유지한다. 즉 요청마다 토큰을 검증하는 무상태(stateless) 방식이 아니라, **상태 유지(stateful) 세션 방식**이다.

JWT를 리소스 서버처럼 매 요청 검증하는 구성(`spring-boot-starter-oauth2-resource-server`)은 도입하지 않는다. 화면이 JSP 서버사이드 렌더링이라 세션 방식이 자연스럽고, 프런트에서 토큰을 보관할 필요가 없다.

또한 **애플리케이션의 사용자 원장(`user_master`)은 사전 등록 없이 최초 로그인 시점에 자동 생성(just-in-time provisioning)** 한다. 계정 생성·비밀번호 관리는 Keycloak이 담당하므로 앱은 "이 Keycloak 계정에 대응하는 행이 우리 DB에 있는가"만 보장하면 된다. 등록 시점은 별도 배치나 관리 화면이 아니라 **서블릿 필터**로 잡는다.

## 구성 요소

| 항목 | 값 | 위치 |
|---|---|---|
| 라이브러리 | `spring-boot-starter-oauth2-client` | `build.gradle:33` |
| Spring Boot / Security | 4.0.6 / 7.0.5 | 해석된 의존성 |
| Realm(issuer) | `http://keycloak.jihun.com/realms/MyTechTestSite` | `application.yaml:63` |
| client-id | `MyTechTestSiteAuth` | `application.yaml:56` |
| client-secret | `${KEYCLOAK_CLIENT_SECRET}` (gitignore된 `.env`) | `application.yaml:57` |
| grant type | `authorization_code` | `application.yaml:58` |
| scope | `openid, profile, email` | `application.yaml:59` |
| redirect-uri | `{baseUrl}/login/oauth2/code/{registrationId}` | `application.yaml:60` |
| 사용자명 클레임 | `preferred_username` | `application.yaml:64` |
| 세션 타임아웃 | 60분 | `application.yaml:5` |
| 로그아웃 착지점 | `http://mytechtest.jihun.com/` | `SecurityConfig.java:39` |
| 사용자 자동 등록 필터 | `KeycloakUserProvisioningFilter` | `SecurityConfig.java:77-78` |

`issuer-uri` 하나만 주면 Spring Security가 기동 시 `/.well-known/openid-configuration`을 조회해 authorization/token/userinfo/jwks/end_session 엔드포인트를 자동으로 채운다. 그래서 `application.yaml`에 개별 엔드포인트가 없다.

실제 discovery 응답에서 확인된 값:

```
authorization_endpoint  .../protocol/openid-connect/auth
token_endpoint          .../protocol/openid-connect/token
userinfo_endpoint       .../protocol/openid-connect/userinfo
end_session_endpoint    .../protocol/openid-connect/logout
jwks_uri                .../protocol/openid-connect/certs
backchannel_logout_supported   true
```

## 인증 흐름 전체

```
[브라우저] http://mytechtest.jihun.com/user/dashboard  (미인증)
     │
     ▼
[앱] AuthorizationFilter — anyRequest().authenticated()          SecurityConfig:59
     │ 미인증 → AuthenticationEntryPoint
     │ ① 브라우저 요청(Accept: text/html) → 302 /oauth2/authorization/keycloak
     │ ② AJAX/SSE 요청               → 401 (리다이렉트 안 함)   SecurityConfig:71-74
     ▼
[앱] OAuth2AuthorizationRequestRedirectFilter
     │ ★ 여기서 HttpSession에 인가요청(state·nonce·PKCE verifier) 저장
     │ 302 →
     ▼
[Keycloak] /protocol/openid-connect/auth
     │ response_type=code, client_id, scope=openid profile email,
     │ state, nonce, code_challenge, code_challenge_method=S256
     │ 로그인 폼 → 사용자 인증 → Keycloak이 자체 SSO 세션 생성(KEYCLOAK_SESSION 등)
     │ 302 →
     ▼
[앱] GET /login/oauth2/code/keycloak?code=...&state=...
     │ OAuth2LoginAuthenticationFilter
     │  1. 세션의 인가요청을 꺼내 state 일치 검증(CSRF 방어)
     │  2. token_endpoint에 code + code_verifier + client_secret 제출 (백채널)
     │  3. ID Token 서명·iss·aud·exp·nonce 검증 (jwks_uri)
     │  4. userinfo 조회 → OidcUser 생성 (name = preferred_username)
     │  5. ★ 세션 고정 보호: 세션 ID 교체(changeSessionId)
     │  6. ★ SecurityContext를 HttpSession에 저장
     │  7. ★ OAuth2AuthorizedClient(access/refresh token)를 HttpSession에 저장
     │ 302 → /user/dashboard                                    SecurityConfig:62
     ▼
[앱] GET /user/dashboard  (로그인 후 첫 인증 요청)
     │ AuthorizationFilter 통과
     ▼
[앱] KeycloakUserProvisioningFilter                          SecurityConfig:77-78
     │ ★ 최초 로그인이면 user_master 에 사용자 INSERT
     │   (세션에 확인 표시 → 이후 요청은 DB 조회 없이 통과)
     ▼
[앱] 이후 모든 요청은 JSESSIONID 쿠키만으로 인증됨 (Keycloak 재조회 없음)
```

### 실측 근거

익명으로 보호 자원을 요청하면 로그인 시작점으로 유도된다.

```
GET /user/dashboard   (Accept: text/html)
→ HTTP/1.1 302
  Location: http://mytechtest.jihun.com/oauth2/authorization/keycloak
```

로그인 시작점은 Keycloak 인가 엔드포인트로 302를 준다. **PKCE(S256)가 자동으로 적용**된다.

```
GET /oauth2/authorization/keycloak
→ HTTP/1.1 302
  Location: http://keycloak.jihun.com/realms/MyTechTestSite/protocol/openid-connect/auth
    response_type         = code
    client_id             = MyTechTestSiteAuth
    scope                 = openid profile email
    state                 = zRHKwb9lToUOc0UT5L9S2laZOBGxRpJDu9QnNLfMrFA=
    nonce                 = OQ_GCnL3A4dOPgxRmISGdoZ8pVelB9Py28OEId4rUtE
    redirect_uri          = http://mytechtest.jihun.com/login/oauth2/code/keycloak
    code_challenge        = cM-qfwS1-MrbbvfEdcSlimVBVKYPlKbl8hWqL4x1mF0
    code_challenge_method = S256
```

AJAX/SSE 경로는 302 대신 401로 끊긴다(요구 ②).

```
GET /user/rag/sessions   (Accept: application/json)      → HTTP/1.1 401
GET /user/rag/docs       (Accept: text/event-stream)     → HTTP/1.1 401
```

이 분기가 없으면 jqGrid/fetch가 로그인 HTML을 JSON으로 파싱하려다 깨진다. 클라이언트는 `csrf.js:48-50`의 전역 핸들러가 401/403을 받으면 페이지를 새로고침해 정상 로그인 흐름으로 복귀시킨다.

## 세션 분석 — 인증 후 세션이 만들어지는가

**결론: 만들어진다.** 그것도 두 개의 서로 다른 세션이 만들어지며, 수명 주기가 서로 독립적이다.

### 1) 세션은 로그인 이전에 이미 생성된다

익명 상태로 랜딩 페이지를 요청한 시점에 이미 JSESSIONID가 발급된다.

```
GET /                 (익명)
→ HTTP/1.1 200
  Set-Cookie: JSESSIONID=5A6BFD2566A67F1F6AE24FE13CF8C886; Path=/; HttpOnly
```

`/`는 `PUBLIC_PATHS`(`SecurityConfig:42`)로 인증 대상이 아닌데도 세션이 생긴다. 원인은 Spring Security가 아니라 **JSP**다. JSP는 `session="true"`가 기본값이라 `home.jsp` 렌더링만으로 `HttpSession`이 생성된다.

이어서 `/oauth2/authorization/keycloak`에 진입하면 이 세션에 **인가요청 객체(state, nonce, PKCE code_verifier)** 가 저장된다(`HttpSessionOAuth2AuthorizationRequestRepository`). 콜백에서 state를 대조하려면 서버가 원본을 기억해야 하므로, 로그인 성공 전부터 세션이 필수다.

### 2) 인증 성공 시 세션 ID가 교체되고 인증 정보가 적재된다

`OAuth2LoginAuthenticationFilter`가 성공 처리를 하면서 세 가지가 일어난다.

| 시점 | 동작 | 담당(기본 구성) |
|---|---|---|
| 성공 직후 | **세션 ID 교체**(기존 세션 데이터는 유지) | `ChangeSessionIdAuthenticationStrategy` |
| 성공 직후 | `SecurityContext`(= `OAuth2AuthenticationToken` + `OidcUser`)를 세션에 저장 | `HttpSessionSecurityContextRepository` |
| 성공 직후 | `OAuth2AuthorizedClient`(access token, refresh token)를 세션에 저장 | `HttpSessionOAuth2AuthorizedClientRepository` |

세션 ID 교체는 세션 고정 공격(session fixation) 방어다. 로그인 전 발급된 JSESSIONID를 공격자가 미리 심어두더라도 인증 순간 무효가 된다.

저장 위치가 전부 `HttpSession`이라는 점이 중요하다. 즉 **토큰은 브라우저로 내려가지 않는다.** 브라우저가 가진 것은 JSESSIONID 쿠키뿐이고, ID/access/refresh 토큰은 서버 메모리에만 있다.

### 3) 로그인 이후 요청은 Keycloak을 다시 호출하지 않는다

인증이 끝나면 매 요청은 `SecurityContextHolderFilter`가 세션에서 `SecurityContext`를 복원하는 것으로 끝난다. 토큰 만료 검사도, Keycloak 재조회도 없다. 따라서 **실질적인 로그인 유효기간은 ID/access token의 exp가 아니라 앱의 세션 타임아웃 60분**(`application.yaml:5`)이다.

애플리케이션 코드가 인증 주체를 읽는 곳은 두 군데뿐이다.

- JSP 4개의 `request.getUserPrincipal()` — 상단바 사용자명 표시
- `KeycloakUserProvisioningFilter` — `SecurityContextHolder`에서 `OidcUser`를 꺼내 자동 등록 판정에 사용(세션을 직접 다루는 유일한 앱 코드이기도 하다)

```java
java.security.Principal principal = request.getUserPrincipal();
String loginName = (principal != null) ? HtmlUtils.htmlEscape(principal.getName()) : "";
```

`getName()`이 Keycloak 계정명으로 나오는 이유는 `user-name-attribute: preferred_username`(`application.yaml:64`) 설정 때문이다. 이 값이 없으면 `sub`(UUID)가 표시된다.

### 4) 앱 세션과 Keycloak SSO 세션은 별개다

| | 앱 세션 | Keycloak SSO 세션 |
|---|---|---|
| 식별자 | `JSESSIONID` (앱 도메인) | `KEYCLOAK_SESSION` / `AUTH_SESSION_ID` (Keycloak 도메인) |
| 저장소 | Tomcat 메모리 | Keycloak DB |
| 수명 | 60분 (유휴 기준) | Realm의 SSO Session Idle/Max |
| 소멸 시점 | 타임아웃 또는 `/logout` | Keycloak 로그아웃 또는 realm 정책 |

두 세션의 수명이 다르기 때문에 **Keycloak에서 세션이 끊겨도 앱 세션은 60분간 그대로 살아있다.** 관리자가 Keycloak 콘솔에서 사용자 세션을 강제 종료해도 이 앱은 계속 인증된 상태로 동작한다. Keycloak은 `backchannel_logout_supported: true`를 광고하지만 앱이 백채널 로그아웃 핸들러를 등록하지 않았기 때문이다(→ 결과 섹션 참조).

### 5) 세션 만료 시 동작

60분 유휴로 세션이 사라지면 요청 종류에 따라 응답이 갈린다.

| 요청 | 응답 | 이유 |
|---|---|---|
| 화면 이동(GET, text/html) | 302 → Keycloak 로그인 | 기본 entry point |
| 조회 AJAX/SSE | **401** | `SecurityConfig:71-74` |
| 변경 요청(POST/PATCH) | **403** | 세션 소멸로 CSRF 토큰 무효 |

401/403 모두 `csrf.js:48-50`이 `window.location.reload()`로 처리해 사용자는 로그인 화면으로 자연스럽게 이동한다. 단 Keycloak SSO 세션이 아직 살아 있으면 재입력 없이 곧바로 되돌아온다(SSO 효과).

## 로그아웃 흐름

```
[브라우저] POST /logout  (+ CSRF 토큰 hidden input)          dashboard.jsp:23-26
     ▼
[앱] LogoutFilter
     │ invalidateHttpSession(true)  — 앱 세션 파기          SecurityConfig:65
     │ clearAuthentication(true)    — SecurityContext 비움   SecurityConfig:66
     │ deleteCookies("JSESSIONID")  — 쿠키 삭제              SecurityConfig:67
     ▼
[앱] OidcClientInitiatedLogoutSuccessHandler                 SecurityConfig:64,86-88
     │ 302 → Keycloak end_session_endpoint
     │       ?id_token_hint=...&post_logout_redirect_uri=http://mytechtest.jihun.com/
     ▼
[Keycloak] SSO 세션까지 종료 → 302 → http://mytechtest.jihun.com/
     ▼
[앱] home.jsp — request.getUserPrincipal() == null → "로그인" 버튼 표시
```

`id_token_hint`를 실으려면 ID Token이 필요하고, 그 ID Token은 앱 세션에 있다. 그래서 **세션 무효화보다 핸들러가 먼저 ID Token을 확보**하는 순서가 성립해야 한다(Spring Security가 로그아웃 성공 핸들러에 `Authentication`을 넘겨주므로 보장됨).

`POST_LOGOUT_REDIRECT_URI`(`SecurityConfig:39`)는 Keycloak 클라이언트의 *Valid post logout redirect URIs* 에 등록돼 있어야 한다. 불일치 시 Keycloak이 리다이렉트를 거부한다.

로그아웃이 POST인 이유는 CSRF 보호 때문이다. GET 로그아웃은 `<img src="/logout">` 같은 방식으로 강제 로그아웃시킬 수 있다.

## 최초 로그인 시 사용자 자동 등록(user_master)

Keycloak은 "누구인지"만 알려줄 뿐, 애플리케이션 데이터(프로젝트·채팅 세션 등)의 소유자로 쓸 **DB 상의 사용자 행**은 별도로 필요하다. 이를 최초 로그인 시점에 자동으로 만든다.

### 구성 파일

| 역할 | 파일 |
|---|---|
| 필터 | `filter/KeycloakUserProvisioningFilter.java` |
| 서비스 | `service/UserMasterService.java` |
| Mapper 인터페이스 | `mapper/UserMasterMapper.java` |
| Mapper XML | `resources/mapper/UserMasterMapper.xml` |
| DTO | `dto/UserMasterDTO.java` |
| 체인 등록 | `SecurityConfig.java:77-78` |

### 실행 위치와 시점

필터는 `AuthorizationFilter` **뒤**, 즉 보안 체인의 마지막에 등록된다(`SecurityConfig:77-78`). 인가를 통과한 요청만 도달하므로 인증 여부를 다시 판단할 필요가 없다.

```java
.addFilterAfter(new KeycloakUserProvisioningFilter(userMasterService),
                AuthorizationFilter.class);
```

주의할 점은 **OAuth2 콜백 요청에서는 실행되지 않는다**는 것이다. `/login/oauth2/code/keycloak` 요청은 `OAuth2LoginAuthenticationFilter`가 인증 성공 후 리다이렉트를 내보내며 종료시키므로 체인 끝까지 내려오지 않는다. 따라서 실제 등록은 **로그인 직후 첫 인증 요청**(기본값 `/user/dashboard`)에서 일어난다. 사용자 체감상으로는 "최초 로그인 시"와 동일하다.

`new`로 직접 생성해 넣는 이유는 Spring Boot가 `Filter` 타입 빈을 서블릿 컨테이너에도 자동 등록해 **보안 체인과 서블릿 체인에서 이중 실행**되는 것을 피하기 위해서다.

### 판정 로직

```
필터(KeycloakUserProvisioningFilter)
  ① SecurityContext 의 principal 이 OidcUser 인가?         :94   아니면 통과(익명 포함)
  ② 세션에 PROVISIONED 표시가 있는가?                       :60   있으면 통과(DB 조회 없음)
  ③ UserMasterService.registerIfAbsent(oidcUser) 호출       :74
  ④ 성공 시에만 세션에 PROVISIONED 표시                      :75

서비스(UserMasterService.registerIfAbsent)                  :44
  ① keycloak_id = oidcUser.getSubject()                     :46
  ② selectUserMasterByKeycloakId 로 조회                     :54
  ③ 있으면 false 반환(아무것도 안 함)
  ④ 없으면 UUID 발급 후 insertUserMaster                     :62,80
```

식별 기준은 PK가 아니라 `keycloak_id`다. `user_id`는 애플리케이션이 매번 새로 만드는 UUID이므로 PK 충돌로는 중복 여부를 알 수 없다.

### 컬럼 매핑

| 컬럼 | 출처 |
|---|---|
| `user_id` | `UUID.randomUUID().toString()` — 앱이 생성하는 PK |
| `keycloak_id` | `oidcUser.getSubject()` (ID Token 의 `sub`) |
| `user_name` | `preferred_username` 클레임 |
| `email` | `email` 클레임 |
| `display_name` | `name` 클레임 (없으면 `preferred_username`) |
| `status` | 고정값 `ACTIVE` |
| `last_login_date` / `create_date` / `update_date` | mapper XML 의 `NOW()` |
| `create_user_id` / `update_user_id` | 자기 자신의 `user_id` (자동 등록이므로) |

`sub`가 아닌 `preferred_username`을 키로 쓰지 않은 이유는, Keycloak에서 사용자명은 변경될 수 있지만 `sub`는 불변이기 때문이다. 모든 문자열 값은 `varchar(100)` 초과 시 잘라서 저장한다(`UserMasterService:100`).

### 중복 등록 방지

`user_id`가 매번 새 UUID라 PK 제약으로는 중복을 막을 수 없다. 그래서 INSERT 문 자체에 존재 검사를 넣었다(`UserMasterMapper.xml:77`).

```sql
INSERT INTO user_master (...)
SELECT #{userId}, #{keycloakId}, ...
WHERE NOT EXISTS (SELECT 1 FROM user_master WHERE keycloak_id = #{keycloakId})
```

조회와 INSERT 사이에 다른 요청이 먼저 등록하더라도 중복 행이 생기지 않는다. 실제 DB에서 확인한 결과는 다음과 같다.

```
1차 INSERT (신규)                        → INSERT 0 1
2차 INSERT (동일 keycloak_id, 다른 UUID) → INSERT 0 0
SELECT count(*) WHERE keycloak_id = ...  → 1
```

다만 이는 경쟁 구간을 좁힐 뿐 완전히 없애지는 못한다. READ COMMITTED에서는 존재하지 않는 행에 잠금이 걸리지 않아, 두 트랜잭션이 동시에 `NOT EXISTS`를 통과할 수 있다. 완전한 차단은 DB 제약이 필요하다.

```sql
CREATE UNIQUE INDEX user_master_keycloak_id_uk ON public.user_master (keycloak_id);
```

### 세션 가드와 실패 정책

필터는 모든 요청에서 실행되므로 매번 DB를 조회하면 낭비다. 그래서 세션에 확인 완료 표시를 남겨 **세션당 최대 1회**만 조회한다. 표시는 **성공했을 때만** 남기므로, DB 장애로 실패하면 다음 요청에서 자동으로 재시도된다.

등록 실패는 예외를 전파하지 않고 로그만 남긴다(fail-open, `KeycloakUserProvisioningFilter:80`). 사용자 등록은 부가 작업인데 여기서 예외를 던지면 화면 전체가 500이 되기 때문이다. 대신 `user_master`에 행이 없는 상태로 로그인이 유지될 수 있으므로, 등록 실패 로그는 모니터링 대상이다.

## 부수적으로 확인된 설계 포인트

**JSP forward에 대한 인가 예외** — `dispatcherTypeMatchers(FORWARD, ERROR).permitAll()`(`SecurityConfig:57`). 컨트롤러가 뷰 이름을 리턴하면 `/WEB-INF/views/**`로 내부 forward가 일어나는데, 이 내부 dispatch까지 인가 대상이 되면 매 화면이 차단된다. 컨테이너가 `/WEB-INF` 직접 접근을 막으므로 최초 REQUEST 인가만으로 충분하다.

**리버스 프록시 대응** — `forward-headers-strategy: framework`(`application.yaml:6`). nginx 뒤에 있으므로 `X-Forwarded-*`를 반영해야 `{baseUrl}`이 `http://mytechtest.jihun.com`으로 전개된다. 이 설정이 없으면 redirect_uri가 `http://localhost:8080/...`으로 만들어져 Keycloak이 거부한다.

**CSRF** — 기본값(세션 저장소) 유지. JSP가 `<meta name="_csrf">`로 토큰을 내리고 `csrf.js`가 모든 비-GET 요청 헤더에 부착한다.

## 결과(Consequences)

### 장점

- 계정·비밀번호·MFA·소셜 로그인을 앱이 전혀 다루지 않는다. 자격증명이 앱을 통과하지 않는다.
- 토큰이 브라우저에 노출되지 않는다(세션 쿠키만 내려감). XSS로 토큰을 탈취당할 표면이 없다.
- PKCE(S256) + state + nonce가 기본 적용되어 인가코드 가로채기·CSRF·재생 공격에 대한 방어가 자동으로 확보된다.
- `issuer-uri` 하나로 엔드포인트가 자동 구성되어 설정이 짧다.
- 사용자 사전 등록 절차가 없다. Keycloak에 계정을 만들면 최초 로그인 시 `user_master` 행이 자동 생성된다. 관리 화면이나 동기화 배치를 따로 만들 필요가 없다.

### 단점과 미완성 지점

**1. 등록된 사용자가 아직 데이터 소유자로 쓰이지 않는다.** 자동 등록으로 "사용자 원장을 만드는" 절반은 해결됐지만, 그 사용자를 실제 데이터에 연결하는 나머지 절반이 남아 있다.

```java
// ChatService.java:32
public static final String DEFAULT_USER_ID = "sunjeehun";   // "인증 미도입 상태의 고정 사용자 식별자"

// AIRestController.java:76
param.put("chatOwnerUserId", "sunjeehun");                  // 하드코딩된 소유자 필터
```

`user_master`에는 로그인한 사용자별 행이 쌓이지만, 다른 테이블의 `create_user_id`/`update_user_id`/`chat_owner_user_id`는 여전히 고정 문자열이다. 결과적으로 **누가 로그인하든 동일 사용자의 데이터를 보고 쓴다.** 채팅 세션 목록도 사용자별로 분리되지 않는다.

해결 방향은 `@AuthenticationPrincipal OidcUser`로 `sub`를 받아 `user_master.user_id`를 조회하고, 그 값을 서비스 계층의 소유자/감사 컬럼에 전달하는 것이다. 이때 두 가지를 결정해야 한다.

- 감사 컬럼의 표기 기준 — 기존 행은 `"sunjeehun"`(사용자명)인데 `user_master.user_id`는 UUID다. 값 체계가 섞이므로 기준을 정하고 기존 데이터를 정리해야 한다.
- 요청마다 `user_master`를 조회할지, 로그인 시 확보한 `user_id`를 세션에 캐시할지.

또한 `AIRestController`의 스트리밍 경로는 `boundedElastic` 스레드로 넘어가므로 `SecurityContextHolder`(ThreadLocal)를 그대로 읽으면 값이 비어 있을 수 있다. 컨트롤러 진입 시점에 사용자 식별자를 지역 변수로 확보해 넘겨야 한다.

**2. 자동 등록은 최초 1회뿐이고 이후 갱신이 없다.** `registerIfAbsent`는 이름 그대로 "없으면 등록"만 한다. Keycloak에서 이메일이나 이름을 바꿔도 `user_master`는 옛 값을 유지하고, `last_login_date`도 최초 등록 시각에 멈춰 있다. 매 로그인마다 `last_login_date`와 프로필을 갱신하려면 UPDATE 경로를 추가해야 한다(이번 범위는 등록까지).

**3. 권한(Role) 기반 인가가 없다.** `anyRequest().authenticated()`뿐이라 로그인한 모든 사용자가 모든 기능에 접근한다. Keycloak realm/client role은 `realm_access.roles` 클레임으로 오지만 `GrantedAuthority`로 매핑하지 않아 `hasRole()`을 쓸 수 없다. 필요해지면 `GrantedAuthoritiesMapper`를 추가해야 한다.

**4. 백채널 로그아웃 미구현.** Keycloak은 `backchannel_logout_supported: true`인데 앱은 `oidcLogout()`/`OidcBackChannelLogoutHandler`를 등록하지 않았다. 그래서 Keycloak 측에서 세션을 끊어도(관리자 강제 로그아웃, 비밀번호 변경 등) 앱 세션은 최대 60분간 유효하다. 즉시 반영이 필요하면 백채널 로그아웃을 도입해야 한다.

**5. 토큰 갱신이 일어나지 않는다.** refresh token이 세션에 저장돼 있지만 앱이 Keycloak을 재호출하는 지점이 없어 사용되지 않는다. 현재 구조에서는 문제가 없으나, 향후 사용자 토큰으로 외부 API를 호출한다면 access token 만료를 직접 처리해야 한다.

**6. 세션이 인메모리라 수평 확장이 제한된다.** Tomcat 로컬 세션이므로 인스턴스를 늘리면 sticky session이 필요하고, 재기동 시 전원 로그아웃된다. 필요해지면 Spring Session(JDBC/Redis)으로 외부화한다.

**7. 평문 HTTP 구간.** issuer와 redirect_uri가 모두 `http://`다. 로컬 개발 환경이라 감수하지만, 운영이라면 인가코드와 JSESSIONID가 평문으로 흐르므로 HTTPS와 `Secure`/`SameSite` 쿠키 속성이 필수다.

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-19 | 최초 작성 — Keycloak OIDC 로그인/로그아웃 흐름과 세션 분석 |
| 2026-08-19 | 최초 로그인 시 `user_master` 자동 등록 기능 추가 반영 (필터·서비스·매퍼 구성, 중복 방지, 실패 정책) |

## 관련 문서

- [0001. pgvector를 벡터 저장소로 사용](0001-use-pgvector.md)
- [0002. RAG 채팅 파이프라인과 가드레일 위치(LiteLLM 위임)](0002-rag-guardrail-litellm.md)
