# guiSample — 기술테스트 시스템

Spring Boot 4 + Spring AI(RAG) + MyBatis + PostgreSQL(pgvector) + JSP 로 만든 사내 기술 검증용 토이 프로젝트입니다.
Keycloak SSO 로 로그인한 사용자가 프로젝트를 관리하고, 문서를 색인해 RAG 챗봇으로 질의하고, Flowable 워크플로우로 업무를 단계별로 진행합니다.

## 주요 기능

| 기능 | 내용 |
|---|---|
| **로그인 (SSO)** | Keycloak OIDC 로그인. 첫 인증 요청에서 `user_master` 에 사용자를 자동 등록하고, 이후 모든 조회는 로그인 사용자 기준으로 걸러집니다. |
| **프로젝트 관리** | 프로젝트 목록 조회(검색·페이징), 등록·수정·삭제 팝업. 요청 ID(`req_id`)·저장소 URL(`repo_url`)·파일명(`file_names`) 입력, TinyMCE 설명 에디터 |
| **REPO동기화** | 프로젝트의 GitHub 저장소에서 최근 30일 커밋을 읽어, 커밋 메시지의 `{요청ID}` 가 `req_id` 와 일치하는 커밋의 변경 파일로 `file_names` 를 채웁니다. 저장소 종류는 확장 가능하게 추상화되어 있습니다(SVN 등). |
| **REPO PUSH** | 프로젝트 수정 팝업의 **REPO PUSH** 버튼으로 저장된 프로젝트 설명(HTML)을 저장소의 `src/docs/{project_id}.txt` 로 커밋합니다. 커밋 메시지는 `프로젝트 설명 yyyy-MM-dd`. |
| **저장소끌어오기** | 저장소 URL·브랜치명을 넣고 **끌어오기**를 누르면 그 브랜치 최신 커밋의 파일 목록을 GitHub 처럼 폴더 탐색형 그리드로 보여 줍니다(서버에 clone 하지 않고 API 로 트리만 조회). 점검완료 버튼은 아직 동작이 없습니다. |
| **지식베이스 색인** | 카테고리 등록 → 텍스트 파일 업로드 → 800토큰 청킹 → 임베딩 → pgvector 적재. 업로드한 파일·청크 목록 조회 |
| **RAG 챗봇** | dense(pgvector) + BM25 키워드 검색을 RRF 로 융합한 하이브리드 검색, 카테고리 한정 검색, SSE 토큰 스트리밍, 대화 세션·메시지(토큰 사용량 포함) 저장 |
| **가드레일** | 모든 LLM 호출은 LiteLLM 프록시를 거치며, 가드레일에 차단되면 오류 대신 "안전 정책에 따라 답변할 수 없습니다" 안내 문구로 응답합니다. |
| **워크플로우** | Flowable BPMN 엔진 기반 `스텝1 → 스텝2 → 스텝3 → 종료` 순차 흐름. 단계별 화면에서 태스크 생성·완료, 종료 건 조회 |

## 기술 스택

| 영역 | 기술 |
|---|---|
| 언어/런타임 | **Java 21** (Gradle toolchain) |
| 프레임워크 | Spring Boot 4.0.6, Spring MVC, Spring Security (OAuth2 Client) |
| AI | Spring AI 2.0.0-M4 — chat `gpt-4o-mini`, embedding `text-embedding-3-large`(1536차원), LiteLLM 프록시 경유 |
| 벡터 스토어 | PostgreSQL + pgvector (HNSW, cosine, 1536차원) + BM25 키워드 테이블 |
| 데이터 접근 | MyBatis 4.0.1 (mybatis-spring-boot-starter) |
| 워크플로우 | Flowable 8.0.0 (`flowable-spring-boot-starter-process`), 전용 PostgreSQL |
| 인증 | Keycloak (OIDC) |
| 외부 연동 | GitHub REST API (Spring `RestClient`) |
| 뷰 | JSP, jQuery + free-jqGrid, TinyMCE 6 (로컬 서빙), 단일 `common.css` |
| 빌드 | Gradle 9.4.1 (Wrapper), WAR 패키징 |
| 테스트 | JUnit 5 단위 테스트, Playwright E2E (`e2e/`) |
| 보조 | Lombok, Spring Boot Actuator(health) |

## 실행 환경 구성

앱은 아래 외부 구성요소가 모두 떠 있어야 정상 동작합니다. 이 저장소에는 docker-compose 파일이 없으며, 각 구성요소는 별도로 띄웁니다.

```
브라우저 ──► nginx (mytechtest.jihun.com:80) ──► Spring Boot 앱 (:8080)
                                                  ├─► pgvector      (localhost:5432)  앱 DB · 벡터 스토어
                                                  ├─► flowable-db   (localhost:5433)  워크플로우 엔진 DB
                                                  ├─► Keycloak      (keycloak.jihun.com)  SSO
                                                  ├─► LiteLLM       (litellm.jihun.com)   OpenAI 프록시 · 가드레일
                                                  └─► GitHub API    (api.github.com)      REPO동기화·REPO PUSH·저장소끌어오기
```

`/etc/hosts` 에 도메인을 등록합니다.

```
127.0.0.1  mytechtest.jihun.com
127.0.0.1  keycloak.jihun.com
127.0.0.1  litellm.jihun.com
```

### 데이터베이스 준비

| 대상 | 방법 |
|---|---|
| `vector_store` | 기동 시 자동 생성 (`spring.ai.vectorstore.pgvector.initialize-schema: true`) |
| Flowable `ACT_*` 테이블 | 기동 시 엔진이 자동 생성 (`flowable.database-schema-update: true`) — **flowable-db(5433)** |
| `chat_master`, `chat_detail` | `src/main/resources/db/chat_schema.sql` 수동 적용 |
| BM25 키워드 테이블 | `src/main/resources/db/bm25_hybrid_schema.sql` 수동 적용 — **코드보다 먼저**. 없으면 키워드 검색이 조용히 빠지고 dense 검색만 동작 |
| `task_master` | `src/main/resources/db/task_master_schema.sql` 수동 적용 — **앱 DB(5432)** 에 적용 (워크플로우 DB 아님) |
| `user_master`, `project_master`, `category_master`, `knowledge_files` | DDL 파일 없음 — 직접 생성 (컬럼은 `resources/mapper/*.xml` 의 resultMap 참고) |

`db/*.sql` 은 부팅 시 자동 실행되지 않습니다.

## 설정

### 프로파일

- `application.yaml` 은 프로파일 선택만 합니다: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:local}`
- 실제 설정은 `application-local.yaml` / `application-e2e.yaml` 에 **각각 완전한 형태로** 들어 있습니다(차이는 채팅 모델명 `gpt-4o-mini` vs `e2e-mock`). 설정을 바꿀 때는 두 파일을 함께 고칩니다.
- 두 파일은 **gitignore 대상**이므로 새 환경에서는 직접 준비해야 합니다.

### 비밀값 (`.env`)

프로젝트 루트의 `.env`(gitignore)가 `spring.config.import: optional:file:.env[.properties]` 로 주입됩니다. `.env.example` 을 복사해 채웁니다.

```properties
OPENAI_API_KEY=            # LiteLLM 프록시 키
KEYCLOAK_CLIENT_SECRET=    # Keycloak 클라이언트 시크릿
UPLOAD_FILE_PATH=          # 지식파일 업로드 저장 경로
FLOWABLE_DB_PASSWORD=      # 워크플로우 전용 DB 비밀번호
PGVECTOR_USERNAME=         # 앱 DB 계정
PGVECTOR_PASSWORD=
GITHUB_TOKEN=              # REPO동기화·저장소끌어오기는 선택(없으면 공개 저장소만·시간당 60회), REPO PUSH는 필수(contents:write)
```

## 빌드 & 실행

```bash
./gradlew bootRun                                # 임베디드 Tomcat 실행 (DevTools), 기본 프로파일 local
SPRING_PROFILES_ACTIVE=e2e ./gradlew bootRun     # e2e 프로파일로 실행
./gradlew build                                  # 컴파일 + 단위 테스트 + WAR 패키징
./gradlew test                                   # 단위 테스트만
./gradlew clean build
```

- 접속: <http://mytechtest.jihun.com> (nginx 경유) → 로그인 후 `/user/dashboard`
- WAR 산출물: `build/libs/guiSample-0.0.1-SNAPSHOT.war`
- 배포 모드: 임베디드(`GuiSampleApplication`) / 외부 WAR(`ServletInitializer`) 모두 지원

## 테스트

| 종류 | 위치 | 실행 | 범위 |
|---|---|---|---|
| 단위 테스트 | `src/test` | `./gradlew test` | REPO동기화 매칭·갱신 규칙, REPO PUSH 경로·커밋 메시지·거부 조건, GitHub URL 파싱, 저장소끌어오기 트리 변환·정렬·브랜치 URI (스프링 컨텍스트·DB·네트워크 없음) |
| E2E | `e2e/` (별도 npm 프로젝트) | `cd e2e && npm test` | 로그인, 프로젝트, 채팅, 하이브리드 검색, 워크플로우, 저장소끌어오기 |

E2E 는 앱과 외부 구성요소가 모두 떠 있어야 합니다. 누락 시 `setup/preflight.ts` 가 무엇을 띄워야 하는지 알려 주고 중단합니다. 최초 설정과 자주 막히는 지점은 [`e2e/README.md`](e2e/README.md) 를 참고하세요.

```bash
cd e2e
npm install && npm run install:browser   # 최초 1회
npm test                                 # @guardrail 태그 제외
npm run test:all                         # 전체 (가드레일 백엔드 필요)
npm run report                           # 리포트 보기
```

Playwright 스펙은 JSP 의 실제 `id`/`class` 셀렉터에 의존하므로, 화면 요소의 id/class 를 바꾸면 E2E 가 깨집니다.

저장소끌어오기 스펙의 `@github` 태그 2건은 실제 GitHub API 를 호출합니다(끌어오기 1회당 3회, 토큰이 없으면 시간당 60회). 한도가 찼거나 오프라인이면 `npx playwright test --grep-invert "@guardrail|@github"` 로 제외하세요.

## 화면

`/` 랜딩(`home.jsp`)과 정적 리소스를 제외한 모든 경로는 로그인이 필요합니다. 화면 라우팅은 `MainController`(`/user`)가 담당합니다.

| 경로 | 화면 | 설명 |
|---|---|---|
| `/` | home.jsp | 랜딩 (로그인 진입) |
| `/user/dashboard` | dashboard.jsp | 대시보드 |
| `/user/projects` | projects.jsp | 프로젝트 목록·등록·수정, REPO동기화, REPO PUSH |
| `/user/rag` | rag.jsp | RAG 챗봇 (세션 목록·카테고리 선택·스트리밍 답변) |
| `/user/embedding` | embedding.jsp | 카테고리 관리, 지식파일 업로드·목록, 청크 조회 |
| `/user/workflow/step1` ~ `step3` | workflow/step1~3.jsp | 단계별 태스크 목록·생성·완료 |
| `/user/workflow/ends` | workflow/ends.jsp | 종료된 태스크 조회 |
| `/user/repositories` | repositories.jsp | 저장소끌어오기 — 저장소 URL·브랜치의 파일 목록을 폴더 탐색형 그리드로 조회 (좌측 메뉴 마지막) |

## REST API

목록 응답은 jqGrid 형식 `{ page, total, records, rows[] }` 를 따릅니다. 조회 결과는 모두 **로그인 사용자 소유 데이터로 제한**됩니다(예외: REPO동기화. 저장소끌어오기는 앱 DB 를 쓰지 않습니다).

### 프로젝트 — `ProjectMasterRestController` (`/user/project`)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/projects` | `page`, `rows`, `projectName`(선택) | jqGrid 목록 |
| GET | `/projects/{projectId}` | — | `{ success, project }` |
| POST | `/projects` | `{ projectName, projectDescription, reqId, repoUrl, fileNames }` | `{ success, projectId }` |
| PATCH | `/projects/{projectId}` | 위와 같음 | `{ success, projectId, message? }` |
| DELETE | `/projects/{projectId}` | — | `{ success, projectId, message? }` — 소유자 프로젝트만 삭제 |
| POST | `/projects/{projectId}/repo-push` | — | `{ success, result: { projectId, repoUrl, path, commitMessage, commitId, created }, message? }` — 소유자 프로젝트만 |
| POST | `/projects/repo-sync` | — | `{ success, result: { lookbackDays, total, updated, unchanged, noMatch, failed, items[] } }` |

담당자(`projectOwnerName`)는 등록 시 로그인 사용자로 고정되며 수정할 수 없습니다.

### RAG 챗봇 · 임베딩 — `AIRestController` (`/user/rag`)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/sessions` | `page`, `rows`(기본 100) | `{ page, total, records, rows[{ sessionId, title, createDate }] }` |
| POST | `/sessions` | `{ title? }` | `{ success, sessionId, title, message }` |
| GET | `/sessions/{sessionId}/messages` | — | `{ sessionId, rows[{ role, content, model, promptTokens, completionTokens, createDate }] }` |
| POST | `/docs` | `{ query, categoryId?, sessionId? }` | `text/event-stream` — 답변 토큰 스트림 |
| POST | `/embedding` | `multipart/form-data` (`file`, `categoryId`) | `{ success, fileId, categoryId, fileName, chunkCount, message }` |

`/docs` 는 실패해도 HTTP 200 으로 안내 문구 한 건을 보내고 정상 종료합니다(가드레일 차단 포함). `sessionId` 가 있으면 질문과 답변(모델·토큰 수 포함)이 `chat_detail` 에 저장됩니다.

### 카테고리 · 지식파일

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/user/category/categories` | `page`, `rows` | jqGrid 목록 |
| POST | `/user/category/categories` | `CategoryMasterDTO` (JSON) | `{ success, categoryId }` |
| GET | `/user/knowledge/knowledges/{categoryId}` | `page`, `rows` | jqGrid 목록 (지식파일) |
| GET | `/user/knowledge/knowledges/chunks/{parentDocumentId}` | — | 청크 목록 배열 |

### 워크플로우 — `WorkflowRestController` (`/user/workflow`)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/tasks` | `step`(`step1`\|`step2`\|`step3`), `page`, `rows` | 해당 단계에 머문 내 태스크 jqGrid 목록 |
| GET | `/finished` | `page`, `rows` | 종료된 내 태스크 jqGrid 목록 |
| POST | `/tasks` | `{ taskName }` | `{ success, taskId, taskName }` |
| POST | `/tasks/{flowableTaskId}/complete` | `{ step }` | `{ success, nextStep, finished }` |

완료 요청에는 화면의 단계(`step`)를 함께 보냅니다. 이미 다음 단계로 넘어간 태스크를 오래된 화면에서 다시 완료하려 하면 서버가 거부합니다.

### 저장소끌어오기 — `RepositoryRestController` (`/user/repository`)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/files` | `repoUrl`, `branch`(비우면 기본 브랜치) | `{ success, page: 1, total: 1, records, rows: [{ path, name, parentPath, type, size, depth, webUrl }], summary: { repoUrl, owner, repo, branch, defaultBranch, commitId, commitMessage, commitDate, truncated, entryCount } }` |

실패해도 HTTP 200 으로 `{ success: false, message, page: 1, total: 0, records: 0, rows: [] }` 를 돌려줍니다. `type` 은 `DIR`·`FILE`·`SYMLINK`·`SUBMODULE`, `size` 는 파일만 값이 있습니다. 전체 트리를 한 번에 내리고 폴더 이동은 화면에서 처리합니다.

## 아키텍처 요약

### 인증과 데이터 소유권

- `SecurityConfig` — 랜딩·정적 리소스·`/actuator/health/**` 외 전부 인증. AJAX/SSE 요청은 로그인 페이지로 302 하지 않고 **401** 을 돌려줍니다.
- `KeycloakUserProvisioningFilter` — 로그인 후 첫 인증 요청에서 사용자를 `user_master` 에 등록합니다.
- `LoginUserSession` — 컨트롤러가 로그인 사용자를 얻는 유일한 통로입니다. 소유권 기준값은 `user_master.user_name` 입니다.
- 소유자 컬럼이 없는 `chat_detail`·`vector_store` 는 상위 엔터티(`chat_master`·`knowledge_files`)의 소유권을 먼저 확인한 뒤 접근합니다. RAG 검색도 내 문서만 근거로 씁니다.

### RAG 파이프라인

```
[색인]  파일 업로드 (file + categoryId)
         → Document(id = fileId, metadata = { category_id })
         → TokenTextSplitter(chunkSize = 800)      ※ 청크 metadata 에 parent_document_id = fileId 자동 주입
         → 임베딩(text-embedding-3-large@1536) → vector_store 적재 + BM25 통계 갱신
         → 원본 파일은 UPLOAD_FILE_PATH 에 저장, knowledge_files 에 기록

[검색]  질문 → ChatClient 어드바이저 체인 → RagContextAdvisor
         → HybridRetrievalService
             ├─ dense: pgvector 코사인 후보 20건 (하한 0.25)
             ├─ keyword: BM25 후보 20건
             └─ RRF(k = 60) 융합 → 상위 8건
         → "관련 문서 없음" 게이트 (dense 최고 유사도 0.32 · 정규화 BM25 2.0 을 둘 다 못 넘으면 컨텍스트 비움)
         → 시스템 메시지에 컨텍스트 주입 → LiteLLM(gpt-4o-mini) → SSE 스트리밍
```

검색 임계값은 임베딩 모델에 종속된 실측값입니다. 모델이나 임계값을 바꾸기 전에 [`src/docs/measurements.md`](src/docs/measurements.md) 를 읽고, 바꾼 뒤에는 같은 형식으로 결과를 추가합니다.

### 워크플로우

- 프로세스 정의: `resources/processes/simple-task.bpmn20.xml` (`simpleTaskProcess`) — `step1 → step2 → step3` 순차 userTask. 기동 시 자동 배포됩니다.
- 진행 상태는 **flowable-db(5433)**, 업무 정보(태스크 이름)는 **앱 DB 의 `task_master`** 에 둡니다. 두 저장소는 `taskId`(`WF-…`) 하나로 이어지며 한 트랜잭션으로 묶이지 않습니다.
- 소유권은 엔진 기능으로 겁니다 — 시작 시 `initiator` 에 로그인 사용자를 넣고 세 단계의 assignee 로 씁니다.

### REPO동기화

- `ScmClient` 인터페이스(`scm` 패키지)가 저장소 조회를 감추고, `ScmClientResolver` 가 `repo_url` 로 구현체를 고릅니다. 현재 구현은 `GitHubScmClient` 하나이며, SVN·GitLab 은 구현체만 추가하면 됩니다.
- 매칭: 커밋 메시지의 `{…}` 값이 `req_id` 와 정확히 같을 때. 갱신: 일치 커밋들의 변경 파일 합집합(정렬, 줄바꿈 구분)으로 `file_names` 를 교체하고, 일치가 없으면 기존 값을 유지합니다.
- 소유권 규칙의 예외로 **전체 프로젝트**를 대상으로 합니다.
- 설정은 `scm.sync.lookback-days`(30), `scm.github.*`(API URL, 호스트, 토큰, 최대 페이지).

### REPO PUSH

- 같은 `ScmClient` 추상화를 씁니다 — 쓰기는 `putFile`(기본 구현은 "지원하지 않음" 예외), GitHub 구현은 Contents API(GET 으로 blob sha 확인 → PUT)로 기본 브랜치에 커밋합니다.
- 대상 경로는 `src/docs/{project_id}.txt`, 내용은 **DB 의 `project_master.project_description`**(TinyMCE HTML), 커밋 메시지는 `프로젝트 설명 yyyy-MM-dd` 입니다.
- 화면에서 저장하지 않은 설명 변경은 올라가지 않습니다(서버가 DB 값을 읽습니다). 팝업이 먼저 그 사실을 확인받습니다.
- REPO동기화와 달리 **소유권 규칙을 그대로 따릅니다**(팝업에서 한 건씩 실행). 설명·저장소 URL 이 비어 있으면 거부합니다 — 빈 파일로 기존 문서를 덮지 않기 위함입니다.
- `GITHUB_TOKEN` 에 **contents:write** 권한이 필요합니다(읽기 전용 토큰이면 403).

### 저장소끌어오기

- 같은 `ScmClient` 추상화에 조회 메서드 `findTree`(기본 구현은 "지원하지 않음" 예외)를 더했습니다. GitHub 구현은 저장소 정보 → 브랜치 → Trees API(`recursive=1`) 3회 호출로 브랜치 최신 커밋의 전체 트리를 읽습니다. 서버에 clone 하지 않습니다.
- `RepoBrowseService` 가 트리를 그리드 행으로 바꿉니다 — 같은 폴더 안에서 폴더 먼저·이름순, 서버 상한 2만 행(넘으면 `truncated`).
- 화면은 전체 행을 받아 두고 폴더 진입·`..`·경로 링크 이동을 브라우저에서만 처리합니다(GitHub 재호출 없음). 파일을 누르면 커밋으로 고정된 GitHub 파일 페이지가 새 탭에서 열립니다.
- 앱 DB 를 쓰지 않습니다. 서버 `GITHUB_TOKEN` 으로 조회하므로 토큰이 읽을 수 있는 비공개 저장소도 로그인 사용자 누구나 볼 수 있습니다.

### 데이터 계층

- `DataSourceConfig` 가 `HikariDataSource` 를 직접 선언하므로 Boot 의 DataSource/MyBatis 자동구성이 꺼집니다. `SqlSessionFactory` 를 직접 구성합니다(언더스코어→카멜케이스, `dto` 타입 별칭, `classpath:mapper/**/*.xml`). Spring AI pgvector 와 MyBatis 가 이 DataSource 하나를 공유합니다.
- `FlowableConfig` 는 워크플로우 엔진에 전용 DataSource·트랜잭션 매니저를 끼웁니다. 앱 기본 트랜잭션 매니저는 `DataSourceConfig` 에서 `@Primary` 로 선언합니다.

## 프로젝트 구조

```
src/main/java/kr/co/jihun/guisample/
├── GuiSampleApplication.java          # 임베디드 부트스트랩
├── ServletInitializer.java            # 외부 WAR 배포
├── advisor/                           # RagContextAdvisor(RAG 컨텍스트 주입), RagRetrievalSettings
├── config/                            # DataSource, Flowable, Security, SpringAi(ChatClient·검색 파라미터)
├── controller/                        # HomeController(랜딩)
│   └── user/                          # MainController(화면), Project·AI·Category·Knowledge·Workflow·Repository REST
├── dto/                               # 요청·응답·도메인 DTO
├── filter/                            # KeycloakUserProvisioningFilter
├── mapper/                            # MyBatis 매퍼 인터페이스
├── scm/                               # ScmClient 추상화 (REPO동기화·REPO PUSH·저장소끌어오기)
│   └── github/                        # GitHub 구현 + 설정
├── service/                           # 비즈니스 로직 (AI·임베딩·하이브리드 검색·채팅·워크플로우·REPO동기화·저장소끌어오기 등)
└── session/                           # LoginUserSession
src/main/resources/
├── application.yaml                   # 프로파일 선택만
├── application-{local,e2e}.yaml       # 실제 설정 (gitignore)
├── mapper/*.xml                       # MyBatis SQL
├── db/*.sql                           # 수동 적용 DDL
├── processes/*.bpmn20.xml             # Flowable 프로세스 정의
└── static/                            # css/common.css, js/(csrf·dialog·workflow), tinymce/
src/main/webapp/WEB-INF/views/
├── home.jsp
├── common/                            # sidebar.jsp(좌측 메뉴), dialog.jsp(공통 대화상자)
└── user/                              # dashboard·projects·rag·embedding, workflow/step1~3·ends
src/test/                              # 단위 테스트
src/docs/                              # ADR, 실측 이력
e2e/                                   # Playwright E2E (별도 npm 프로젝트)
```

## 프론트엔드 규약

- 스타일은 `static/css/common.css` 한 파일에 둡니다(화면별 인라인 스타일 금지). UI 디자인 토큰은 [`DESIGN.md`](DESIGN.md) 참고.
- 좌측 메뉴는 `views/common/sidebar.jsp` 한 곳에만 있고, 각 화면이 정적 include 로 끼웁니다.
- CSRF 토큰은 `static/js/csrf.js` 가 jQuery ajax 에 자동 부착합니다.
- JSP EL 은 자동 이스케이프가 없으므로 사용자 입력 문자열은 `HtmlUtils.htmlEscape` 로 직접 이스케이프합니다.
- 워크플로우·프로젝트 목록·저장소끌어오기 화면은 브라우저 기본 `alert()`/`confirm()` 대신 공통 레이어 팝업(`appDialog`, `views/common/dialog.jsp` + `static/js/dialog.js`)을 씁니다.

## 문서

| 문서 | 내용 |
|---|---|
| [`src/docs/adr/0001`](src/docs/adr/0001-use-pgvector.md) | pgvector 채택 |
| [`src/docs/adr/0002`](src/docs/adr/0002-rag-guardrail-litellm.md) | RAG 파이프라인 · 가드레일 LiteLLM 위임 |
| [`src/docs/adr/0003`](src/docs/adr/0003-keycloak.md) | Keycloak SSO · 세션 관리 |
| [`src/docs/adr/0004`](src/docs/adr/0004-sguard-cancel.md) | sguard 포기 근거 |
| [`src/docs/adr/0005`](src/docs/adr/0005-hybrid-bm25-rrf.md) | 하이브리드 검색(BM25+RRF) · RAG 소유권 |
| [`src/docs/adr/0006`](src/docs/adr/0006-flowable-workflow.md) | 워크플로우(Flowable · 전용 DB · 화면 구성) |
| [`src/docs/adr/0007`](src/docs/adr/0007-github-repo-sync.md) | REPO동기화·REPO PUSH·저장소끌어오기(ScmClient 추상화 · GitHub 구현) |
| [`src/docs/measurements.md`](src/docs/measurements.md) | 검색 품질 · 비용 실측 이력 |
| [`e2e/README.md`](e2e/README.md) | E2E 환경 구성 · 문제 해결 |
| [`DESIGN.md`](DESIGN.md) | UI 디자인 토큰 |
| [`CLAUDE.md`](CLAUDE.md) | 개발 규칙 · 아키텍처 상세 (Claude Code 가이드) |

## 라이선스

내부 샘플 프로젝트.
