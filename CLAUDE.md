# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew bootRun        # 임베디드 Tomcat 실행 (DevTools). 기본 프로파일 local
./gradlew build          # 컴파일 + WAR 패키징 → build/libs/guiSample-0.0.1-SNAPSHOT.war
./gradlew clean build
SPRING_PROFILES_ACTIVE=e2e ./gradlew bootRun   # e2e 프로파일로 기동
```

`./gradlew test` 는 `src/test` 의 순수 단위 테스트(REPO동기화 매칭·GitHub URL 파싱 — 스프링 컨텍스트·DB·네트워크 없음)만 돈다. **화면·DB 를 거치는 실제 테스트는 `e2e/` 의 Playwright 스위트**다 (아래 참조).

Java 툴체인은 **21** (`build.gradle`).

### E2E (Playwright)

`e2e/` 는 Gradle 빌드에 포함되지 않는 별도 npm 프로젝트다.

```bash
cd e2e
npm install && npm run install:browser   # 최초 1회
npm test          # @guardrail 태그 제외 (기본)
npm run test:all  # 전체 — LiteLLM 가드레일 백엔드(LM Studio) 필요
npm run test:ui / npm run report
```

앱을 포함해 pgvector·Keycloak·LiteLLM·nginx 가 **모두 떠 있어야** 하고 `/etc/hosts` 에 `mytechtest.jihun.com` / `keycloak.jihun.com` / `litellm.jihun.com` 이 등록돼 있어야 한다. 누락 시 `setup/preflight.ts` 가 무엇을 어떻게 띄우는지 찍고 중단한다. 상세 절차와 자주 막히는 지점은 `e2e/README.md`.

Playwright 스펙은 JSP 의 실제 `id`/`class` 셀렉터에 직접 의존한다(`chat.spec.ts` 상단 주석에 목록). **JSP 의 id/class 를 바꾸면 e2e 가 깨진다.**

스펙: `auth` / `projects` / `chat` / `hybrid-search` / `workflow`(스텝1~3·종료 수명주기). 워크플로우 화면은 기본 대화상자를 쓰지 않으므로 Playwright 의 `dialog` 이벤트가 아니라 **`#appDialog` 팝업의 버튼을 클릭**해야 진행된다. 스펙은 기본 대화상자가 뜨면 실패하도록 `afterEach` 에서 감시한다.

## 설정 · 프로파일

- `application.yaml` 은 **프로파일 선택만** 한다: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:local}`.
- 실제 설정은 전부 `application-local.yaml` / `application-e2e.yaml` 에 **각각 완전한 형태로** 들어 있다. 두 파일의 현재 유일한 차이는 `spring.ai.openai.chat.options.model` (`gpt-4o-mini` vs `e2e-mock`). **설정을 바꿀 때는 두 파일을 함께 고쳐야 한다.**
- 비밀값은 루트 `.env`(gitignore) 에서 `spring.config.import: optional:file:.env[.properties]` 로 주입: `OPENAI_API_KEY`, `KEYCLOAK_CLIENT_SECRET`, `UPLOAD_FILE_PATH`, `GITHUB_TOKEN`(선택 — 없으면 공개 저장소만·시간당 60회).
- OpenAI 호출은 실제 OpenAI 가 아니라 **LiteLLM 프록시**(`base-url: http://litellm.jihun.com`)를 거친다. 가드레일도 LiteLLM 쪽에 있다.

## 아키텍처

Spring Boot 4 + Spring AI(2.0.0-M4) + MyBatis + PostgreSQL(pgvector) + JSP. 루트 패키지 `kr.co.jihun.guisample`. 임베디드(`GuiSampleApplication`)와 외부 WAR(`ServletInitializer`) 두 배포 모드를 모두 지원한다.

### 단일 DataSource — 자동구성 비활성

`DataSourceConfig` 가 `HikariDataSource` 빈을 직접 선언하므로 **Boot 의 DataSource/MyBatis 자동구성이 꺼진다**. 따라서 `SqlSessionFactory` 도 여기서 명시 구성한다: `mapUnderscoreToCamelCase=true`, `typeAliasesPackage=...dto`, `mapperLocations=classpath:mapper/**/*.xml`. Spring AI pgvector 와 MyBatis 매퍼가 이 DataSource 하나를 공유한다. 커넥션 설정은 `spring.datasource` (Hikari 키 — `jdbc-url`, `maximum-pool-size` 등) 아래에 있다.

### 인증 — Keycloak(OIDC) + 세션 사용자

- `SecurityConfig`: `PUBLIC_PATHS`(랜딩·정적·`/actuator/health/**`) 외 전부 인증. AJAX/SSE 요청(`X-Requested-With` 또는 Accept 가 JSON/event-stream)은 302 대신 **401** 로 끊는다 — 그래야 fetch/jqGrid 가 로그인 HTML 을 파싱하다 죽지 않는다.
- `KeycloakUserProvisioningFilter` 는 **빈으로 만들지 않고** `SecurityConfig` 에서 직접 생성해 `AuthorizationFilter` 뒤에 넣는다(Boot 의 Filter 빈 자동 등록으로 인한 이중 실행 방지). OAuth2 콜백 요청은 여기까지 오지 않으므로 실제 프로비저닝은 **로그인 후 첫 인증 요청**에서 일어난다. 실패는 fail-open(로그만).
- `LoginUserSession` 이 로그인 사용자에 접근하는 **유일한 통로**다. 컨트롤러/서비스는 `SecurityContextHolder`·`OidcUser` 를 직접 뒤지지 않는다. `require()`/`requireUserName()` 은 값이 없으면 예외를 던진다 — 조회 조건이 조용히 빠져 남의 데이터가 노출되는 것을 막기 위해서다.
- **주의**: `LoginUserSession` 은 요청 스레드 바인딩 프록시다. `Schedulers.boundedElastic()` 안에서 호출하면 실패한다. SSE 저장 경로처럼 비동기로 넘기는 값은 **컨트롤러 메서드 본문에서 미리 꺼내 파라미터로 전달**할 것 (`AIRestController.getDocs` 참조).

### 데이터 소유권 규칙

`user_master.user_name` 이 모든 조회의 소유권 필터 키다.

- 소유자 컬럼이 있는 테이블: `chat_master.chat_owner_user_id`, `knowledge_files.create_user_id`.
- 소유자 컬럼이 **없는** `chat_detail` 과 Spring AI 의 `vector_store` 는 **상위 엔터티의 소유권을 먼저 확인**하고(아니면 빈 목록) 접근한다 — `ChatService.selectMessages`, `KnowledgeFileService.selectKnowledgeChunkList`, `HybridRetrievalService.retrieve` 가 그 패턴이다. RAG 검색도 2026-09-17 부터 이 규칙을 따른다(그 전에는 `category_id` 로만 걸러 남의 문서가 답변 근거로 들어올 수 있었다). 소유권 기준값 `userName` 은 컨트롤러가 **요청 스레드에서** 꺼내 `advisors(spec -> spec.param("user_name", ...))` 로 넘긴다.
- jqGrid 계열 목록 컨트롤러에서는 **소유자 조건을 count 호출 전에** param 에 넣는다(총건수와 목록이 같은 조건을 봐야 한다). 페이징 키는 그 뒤에 `startRow`/`pageSize` 로 넣는다 — mapper XML 이 `pageSize != null` 일 때만 LIMIT/OFFSET 을 붙인다.

### RAG 파이프라인

- 검색은 서비스가 아니라 **어드바이저 체인 안**에서 트리거된다: `RagContextAdvisor`(`CallAdvisor`+`StreamAdvisor`, order `HIGHEST_PRECEDENCE+100`)가 `HybridRetrievalService` 를 호출해 **dense(pgvector) + BM25 키워드 검색을 RRF 로 융합**한다(topK=8, 후보 각 20, RRF k=60). 검색 로직이 별도 서비스로 나갔어도 **호출은 여전히 체인 안**이라 실행 순서 계약은 order 로 그대로 보장된다. 키워드 쪽 스키마는 `db/bm25_hybrid_schema.sql`(수동 적용), 설계 근거는 ADR 0005. 시스템 메시지만 `augmentSystemMessage` 로 증강하고 user 메시지는 원문 유지. 컨텍스트 주입은 `String.replace` **리터럴 치환** — 문서 본문의 중괄호가 프롬프트 템플릿으로 재파싱되지 않게 하기 위함이다. 스트리밍 경로의 블로킹 검색은 `boundedElastic` 로 오프로딩한다.
- 카테고리 한정 검색은 `chatClient...advisors(spec -> spec.param("category_id", ...))` 로 넘기고 어드바이저가 `request.context()` 에서 읽어 메타데이터 필터로 변환한다.
- 인덱싱(`EmbeddingService.embed`): 부모 `Document` 의 id 를 `fileId` 로 지정 → `TokenTextSplitter`(chunkSize 800)가 청크 metadata 에 `parent_document_id` 를 자동 주입 → `knowledge_files.file_id` 와 일치. 그래서 청크 조회를 파일 소유권으로 막을 수 있다.
- `POST /user/rag/docs` 는 `text/event-stream` 이라 **예외를 절대 밖으로 던지면 안 된다**. 던지면 Accept 협상 실패로 500 + 본문 없음이 된다. `onErrorResume` 으로 모든 실패를 텍스트 1건 + 정상 종료(200)로 바꾼다. LiteLLM 가드레일 차단은 400 응답 본문의 표식(`GUARDRAIL_MARKERS`)으로 설정 오류 400 과 구분한다.

### 워크플로우 — Flowable

- 설계 근거는 **ADR 0006**. 엔진은 **Flowable 8.0.0**(`flowable-spring-boot-starter-process`). 8.x 가 Spring Boot 4 라인에 맞춘 첫 릴리스다 — **7.x 는 Boot 3 전용이라 쓸 수 없다.**
- **DB 가 앱과 분리돼 있다.** ACT_*/FLW_* 60여 개 테이블은 `flowable-db`(docker-compose, `127.0.0.1:5433/flowable_test`)에 들어간다. `FlowableConfig` 가 전용 `HikariDataSource`+`DataSourceTransactionManager` 를 만들고 `EngineConfigurationConfigurer` 로 엔진에 바꿔 끼운다(오토컨피그는 기본적으로 `@Primary` DataSource = pgvector 를 잡는다). 비밀번호는 `.env` 의 `FLOWABLE_DB_PASSWORD`.
  - 그래서 **도메인 데이터와 워크플로우 상태는 한 트랜잭션에 묶이지 않는다.** 둘을 함께 바꿔야 하면 순서와 보상을 명시할 것(2PC 없음).
  - Flowable 전용 트랜잭션 매니저가 생기면서 Boot 의 트랜잭션 매니저 자동구성이 물러난다. 앱 기본 매니저는 `DataSourceConfig.transactionManager()` 가 `@Primary` 로 직접 선언한다 — **이걸 지우면 `@Transactional` 이 워크플로우 DB 를 잡는다.**
- 프로세스 정의는 `resources/processes/simple-task.bpmn20.xml` 하나(`simpleTaskProcess`): `start → step1 → step2 → step3 → end` 의 순차 userTask. 기동 시 자동 배포되며(`classpath*:/processes/**.bpmn20.xml`), ACT_* 스키마도 엔진이 직접 만든다(`flowable.database-schema-update: true`) — `db/*.sql` 수동 적용 대상이 아니다.
- **소유권**은 엔진 기능으로 건다. 프로세스 시작 시 `initiator` 변수에 `user_master.user_name` 을 넣고 세 userTask 가 `${initiator}` 를 assignee 로 받는다. 목록·완료·이력 조회가 모두 assignee(또는 `initiator` 변수)로 걸리므로 남의 태스크는 결과에 들어오지 않는다. 기준값은 다른 도메인과 같이 **컨트롤러가 `LoginUserSession` 에서 꺼내** 서비스로 넘긴다.
- **저장소가 둘로 나뉜다.** 진행 상태는 엔진 DB, 업무 정보(태스크 이름)는 앱 DB 의 `task_master` 다. 잇는 키는 `task_id`(엔진에서는 business key 이자 프로세스 변수 `taskNo`) 하나이며 물리 FK 는 없다. 생성은 `task_master` INSERT → 프로세스 시작 **순서를 지킨다**(엔진이 실패하면 앱 트랜잭션이 함께 롤백돼 양쪽 다 남지 않는다). 목록은 화면에 보이는 건의 이름만 한 번에 읽고(`selectTaskNameMap`), **이름 행이 없어도 목록은 정상 동작**해야 한다.
- id 가 두 개다: `taskId`(WF-…, 사람이 읽는 업무 식별자 = `task_master.task_id`)와 `flowableTaskId`(단계마다 새로 발급되는 엔진 태스크 id, 완료 요청용). **섞어 쓰지 말 것.**
- 화면은 4개다: userTask 에 대응하는 `step1`/`step2`/`step3` 과, 끝난 프로세스를 조회하는 **`ends`**. 종료는 BPMN 의 단계가 아니라 `HistoryService` 조회 화면이라 `STEP_KEYS` 에 넣지 않고 별도 엔드포인트(`GET /user/workflow/finished`)를 쓴다.
- userTask id(`step1`/`step2`/`step3`)가 REST 의 `step` 파라미터·화면 파일명과 1:1 이다. **id 를 바꾸면 `WorkflowService.STEP_KEYS` 와 스텝 화면 3개를 함께 고쳐야 한다.**
- 완료 요청은 태스크 id 와 함께 **화면의 단계(`step`)를 같이 보낸다** — 오래된 목록으로 이미 넘어간 태스크를 다시 완료하는 것을 서버가 거부한다.

### REPO동기화 — 저장소 커밋 → `project_master.file_names`

- 설계 근거는 **ADR 0007**.
- 프로젝트 목록의 **REPO동기화** 버튼 → `POST /user/project/projects/repo-sync` → `RepoSyncService`. `repo_url`·`req_id` 가 모두 있는 **전체 프로젝트**가 대상이며(**데이터 소유권 규칙의 의도된 예외** — 대상 선정·UPDATE 모두 소유자/사용자 이름 조건 없음, `update_user_id` 에 실행자만 기록), 최근 `scm.sync.lookback-days`(30)일 커밋 메시지의 `{…}` 값이 `req_id` 와 **정확히 일치**하는 커밋들의 변경 파일 합집합(정렬·줄바꿈 구분)으로 `file_names` 를 **교체**한다. 일치 커밋이 없으면 기존 값을 그대로 둔다.
- 저장소 종류는 `scm` 패키지의 **`ScmClient` 인터페이스 뒤에 숨긴다**. `ScmClientResolver` 가 `supports(repoUrl)` 로 구현을 고르므로, GitLab·SVN 등은 **구현체 빈을 하나 추가**하면 되고 `RepoSyncService` 는 손대지 않는다. 현재 구현은 `scm/github/GitHubScmClient` 하나(설정 `scm.github.*`, 두 프로파일 yaml 모두).
- GitHub 는 라이브러리 없이 Spring `RestClient` 로 호출한다(필요한 API 가 2개뿐이고, hub4j 는 Jackson 2·자체 HTTP 스택을 끌고 와 Boot 4 와 겹친다). 목록 API 에 파일이 없으므로 **req_id 가 일치한 커밋만** 상세를 읽는다.
- 외부 HTTP 를 기다리는 동안 커넥션을 잡지 않도록 트랜잭션 없이 프로젝트 단위로 UPDATE 한다. 저장소 하나가 실패해도 나머지는 진행되고, 실패는 결과 항목 `FAILED` 로 돌아온다.

### 프론트 규약 (JSP)

- 뷰는 `src/main/webapp/WEB-INF/views/`, 라우팅은 `MainController`(`/user/**`). 스타일은 `static/css/common.css` 단일 파일(화면별 인라인 스타일 금지).
- 좌측 메뉴는 **`views/common/sidebar.jsp` 한 곳**에만 있다. 각 화면은 include 앞에 `String activeMenu` 를 선언하고 `<%@ include %>`(정적 include)로 끼운다. 런타임 `<jsp:include>` 는 `DispatcherType.INCLUDE` 를 일으키는데 `SecurityConfig` 는 FORWARD/ERROR 만 열어 두었으므로 쓰지 않는다. 조각 파일에는 **`<%@ page pageEncoding="UTF-8" %>` 가 반드시 있어야 한다** — 없으면 포함되는 파일이 ISO-8859-1 로 읽혀 메뉴 한글이 깨진다.
- 알림·확인은 브라우저 기본 `alert()`/`confirm()` 대신 **`appDialog`**(마크업 `views/common/dialog.jsp`, 동작 `static/js/dialog.js`)를 쓴다. 화면은 조각을 정적 include 하고 `dialog.js` 를 `workflow.js` 보다 먼저 읽는다. **기본 대화상자와 달리 스크립트를 멈추지 않으므로** 확인 결과는 `onConfirm`/`onCancel` 콜백으로 이어받아야 한다 — `if (confirm(...))` 식으로 옮겨 적으면 항상 진행돼 버린다. 조각이 없는 화면에서 호출하면 기본 대화상자로 폴백한다.
  현재 적용 범위는 워크플로우 화면 4개다. `projects.jsp`/`rag.jsp` 등은 아직 `alert()` 를 쓴다.
- CSRF: 각 JSP `<head>` 의 `<meta name="_csrf">` / `_csrf_header` 를 `static/js/csrf.js` 가 읽어 jQuery ajax 에 자동 부착하고 fetch 용으로 `window.csrfHeader()` 를 노출한다. 401/403 이면 페이지를 새로고침해 Keycloak 로그인 흐름으로 되돌린다.
- JSP EL 은 자동 이스케이프가 없다 — 사용자 유래 문자열은 `HtmlUtils.htmlEscape` 로 직접 이스케이프한다.
- JSP 내 JS 변수 선언은 `var` 가 아니라 **`let`** 을 쓴다(커밋 90b316b 로 통일).
- REST 목록 응답은 jqGrid shape `{ page, total, records, rows[] }` 를 유지한다.

### 스키마 · 마이그레이션

- `src/main/resources/db/*.sql` 은 **수동 적용** 대상이다. 부팅 시 자동 실행되지 않는다. `bm25_hybrid_schema.sql` 은 특히 **코드보다 먼저** 적용해야 한다(미적용 시 키워드 검색이 조용히 빠지고 dense-only 로 폴백).
- `vector_store` 테이블만 `spring.ai.vectorstore.pgvector.initialize-schema: true` 로 자동 생성된다(HNSW, cosine, 1536차원). 도메인 테이블(`user_master`, `project_master`, `category_master`, `knowledge_files`, `chat_master`, `chat_detail`, `task_master`)은 직접 만들어야 한다. `task_master` 는 `db/task_master_schema.sql` — **앱 DB(5432)에 적용한다. 워크플로우 DB(5433)가 아니다.**
- 매퍼는 interface(`mapper/`)와 XML(`resources/mapper/`)이 짝을 이루며 namespace 가 일치해야 한다. 명시적 `resultMap` 을 쓰고, 동적 조건은 `<sql>` 조각으로 재사용한다(`KnowledgeFileMapper.xml` 이 표준형).
- **매퍼 XML 주석 안에 파라미터 표기(`#{...}`)를 쓰지 말 것.** MyBatis 의 치환은 SQL 주석을 구분하지 않아 주석 속 예시도 실제 바인딩으로 잡힌다. 그러면 MyBatis 는 파라미터를 n+1개 만들고 드라이버는 `?` n개만 보게 되어 `The column index is out of range` 로 죽는다. psql 로 SQL 을 직접 돌려보는 검증으로는 절대 안 잡히므로(주석이 문제라서), **매퍼는 MyBatis 를 통과시켜 확인**해야 한다.

## 문서

- `src/docs/adr/` — 결정 기록: 0001 pgvector 채택, 0002 RAG 파이프라인·가드레일 LiteLLM 위임, 0003 Keycloak SSO·세션 관리, 0004 sguard 포기 근거, 0005 하이브리드 검색(BM25+RRF)·RAG 소유권, 0006 워크플로우 기능 아키텍처(Flowable·전용 DB·화면 구성), 0007 REPO동기화(ScmClient 추상화·GitHub 구현·소유권 예외). **인증/RAG/워크플로우/REPO동기화 흐름을 바꾸기 전에 읽을 것.**
- `src/docs/measurements.md` — 검색 품질·비용 실측 이력. **임베딩 모델이나 검색 임계값을 바꾸기 전에 읽고, 바꾼 뒤에는 같은 형식으로 추가할 것.**
- `_workspace/00_architect_design.md` — 채팅 세션 기능(chat_master/chat_detail) 상세 설계. 나머지 `_workspace/*` 는 하위 에이전트 작업 요약, `_workspace_prev_*` 는 이전 회차 보관본.
- `README.md` — 기능·실행 환경·화면·REST API·아키텍처 요약 (2026-10-13 코드 실사 기준으로 갱신). 기능·API 를 바꾸면 함께 고칠 것.
- `DESIGN.md` — 앱 아키텍처가 아니라 UI 디자인 토큰(Apple 스타일) 스펙이다.

## 하네스: Spring AI/RAG 기능 개발

**목표:** Spring Boot 4 + MyBatis + PostgreSQL(pgvector) + JSP 환경에서 RAG 기능을 백엔드·데이터·프론트 전 계층에 걸쳐 일관되게 추가·수정한다.

**트리거:** RAG/Spring AI/벡터 검색/문서 인덱싱/챗봇 등 RAG 관련 신규·후속 작업 요청 시 `rag-feature-orchestrator` 스킬을 사용하라. 단순 질문(설계 조언, 사용법 문의)이나 1~2줄 수정은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-05-30 | 초기 구성 (에이전트 5, 스킬 6) | 전체 | - |
| 2026-09-09 | 아키텍처·설정·E2E 섹션 보강 (/init) | 전체 | 코드 실사와 문서 불일치 해소 |
| 2026-09-17 | 하이브리드 검색(BM25+RRF)·RAG 소유권 필터 반영 | RAG·소유권·스키마 | ADR 0005 |
| 2026-09-22 | Flowable 워크플로우(스텝1~3 + 종료 조회 화면) 추가 | 워크플로우·DataSource·사이드바 | 전용 DB(flowable-db) 분리, 사이드바 공통화 |
| 2026-10-13 | 프로젝트 req_id/repo_url/file_names 컬럼, REPO동기화(ScmClient 추상화 + GitHub 구현) 추가 | 프로젝트·scm·src/test | 저장소 종류 확장(SVN 등) 대비 |
