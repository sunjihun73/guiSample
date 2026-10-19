---
name: feature-design
description: guiSample 기능의 설계 문서(_workspace/00_architect_design.md)를 작성하는 방법. 목표·범위, 데이터 모델과 DDL 적용 DB, 소유권 처리, REST API(jqGrid shape 포함), 화면 시나리오와 e2e 영향, 분배 지시를 정리하고, 도메인별(RAG 하이브리드 검색·인덱싱·프롬프트 / Flowable 워크플로우 / ScmClient 저장소 연동) 결정 기준을 제공한다. feature-architect 에이전트가 사용한다. "기능 설계", "RAG 설계", "검색 파이프라인 설계", "워크플로우 설계", "저장소 연동 설계", "API·스키마 설계" 요청 시 트리거. 코드 작성 자체는 이 스킬이 아니다.
---

## 사용 시점

feature-architect 가 설계 문서를 쓸 때. **코드를 만들지 않는다.** 결정을 문서화하는 가이드다.

## 먼저 읽을 것

| 도메인 | 읽을 것 |
|--------|---------|
| 공통 | `CLAUDE.md`(아키텍처·데이터 소유권·프론트 규약·스키마), 관련 Service/Mapper/JSP |
| RAG·검색 | ADR 0002·0005, `advisor/RagContextAdvisor`, `service/HybridRetrievalService`, `src/docs/measurements.md` |
| 인증 | ADR 0003, `config/SecurityConfig`, `session/LoginUserSession` |
| 워크플로우 | ADR 0006, `config/FlowableConfig`, `service/WorkflowService`, `processes/simple-task.bpmn20.xml` |
| 프로젝트·SCM | ADR 0007, `scm/ScmClient`, `service/RepoSyncService`·`RepoPushService` |

## 문서 섹션 (모두 포함, 해당 없으면 "해당 없음"과 이유)

### 1. 목표와 범위
사용자 시나리오 1~3개와 비범위 1~2개. 상단에 가정 목록.

### 2. 데이터 모델
- 신규·변경 테이블과 컬럼, DDL 파일명(`src/main/resources/db/{이름}.sql`)
- **적용 DB 명시**: 도메인 테이블은 앱 DB(5432). ACT_*/FLW_* 는 엔진이 만들므로 DDL 을 쓰지 않는다. 워크플로우 DB(5433)에 도메인 테이블을 두지 않는다
- DDL 은 수동 적용이다. 코드보다 먼저 적용해야 하는지(예: `bm25_hybrid_schema.sql`)와 미적용 시 동작을 적는다
- DTO 는 `dto/` 패키지. 매퍼는 interface + XML 한 쌍

### 3. 소유권 처리 (필수)
조회·변경 경로마다 아래 중 하나를 고르고 근거를 적는다.

| 유형 | 방식 | 기존 예 |
|------|------|---------|
| 직접 컬럼 | 소유자 컬럼 = `user_master.user_name` 조건 | `chat_master.chat_owner_user_id`, `knowledge_files.create_user_id` |
| 상위 확인 | 상위 엔터티 소유권 먼저 확인, 아니면 빈 목록 | `ChatService.selectMessages`, `HybridRetrievalService.retrieve` |
| 엔진 기능 | Flowable `initiator`/assignee | `WorkflowService` |
| 의도된 예외 | 근거를 ADR 수준으로 기록 | REPO동기화(ADR 0007) |

기준값은 **컨트롤러가 `LoginUserSession.requireUserName()` 으로 요청 스레드에서 꺼내** 서비스에 넘긴다고 명시한다. 비동기(`boundedElastic`, SSE) 경로가 있으면 미리 꺼내 파라미터로 넘기는 지점을 적는다.

### 4. 처리 흐름 (도메인별 — 아래 "도메인별 결정 기준" 참조)

### 5. API 스펙

| Method | Path | 요청 | 응답 | 소유권 | 비고 |
|--------|------|------|------|--------|------|

- 경로는 `/user/{도메인}/...` (화면 라우팅은 `MainController` 의 `/user/**`)
- **목록은 jqGrid shape `{page, total, records, rows[]}`** — `Map<String,Object>` 로 내려도 된다(기존 컨트롤러와 동일). `rows` 의 원소는 DTO
- 단건·명령 응답은 DTO(예: `RepoPushResult`). 필드명을 빠짐없이
- SSE(`text/event-stream`)면 실패를 어떻게 정상 종료로 바꾸는지 명시(예외를 밖으로 던지지 않음)
- AJAX 미인증은 401 로 끊긴다(`SecurityConfig`) — 새 경로가 `PUBLIC_PATHS` 대상인지 확인

### 6. 화면 시나리오
- 라우트 → `WEB-INF/views/user/...jsp`, 사이드바 `activeMenu` 값
- 사용 컴포넌트: jqGrid, 레이어 팝업, `appDialog`(확인은 `onConfirm` 콜백)
- 추가·변경되는 id/class 목록과 **영향받는 e2e 스펙**(`e2e/specs/*.spec.ts`). 기존 id/class 는 바꾸지 않는 것이 기본
- 스타일은 `common.css` 에만 추가

### 7. 검증 계획
단위 테스트 대상(외부 의존 없는 순수 로직), e2e 영향 스펙, 매퍼 변경 시 MyBatis 통과 확인 방법.

### 8. 분배 지시
- **mybatis-data-engineer**: DDL 파일, Mapper interface/XML, ResultMap
- **spring-backend-engineer**: DTO, Service, Controller, Config, 단위 테스트
- **jsp-frontend-engineer**: JSP, `static/js`, `common.css`, 필요 시 e2e 스펙 갱신
- 화면이 없으면 frontend 를 "해당 없음"으로

## 도메인별 결정 기준

### RAG / 검색
현재 구조를 기본값으로 삼고, 바꾸는 항목만 근거와 함께 적는다.
- **검색은 어드바이저 체인 안**: `RagContextAdvisor`(order `HIGHEST_PRECEDENCE+100`) → `HybridRetrievalService`(dense pgvector + BM25 → RRF, topK=8, 후보 각 20, k=60). `QuestionAnswerAdvisor` 로 바꾸지 않는다 — 하이브리드 검색·소유권 필터·리터럴 치환을 잃는다
- 요청별 값(`category_id`, `user_name`)은 `advisors(spec -> spec.param(...))` 로 넘기고 어드바이저가 `request.context()` 에서 읽는다
- 컨텍스트 주입은 시스템 메시지에 `String.replace` 리터럴 치환(문서 중괄호 재파싱 방지). user 메시지는 원문 유지
- 인덱싱: 부모 Document id = `fileId` → `TokenTextSplitter`(800) 가 `parent_document_id` 주입 → 소유권 필터의 연결 고리. 이 연결을 끊는 설계 금지
- 벡터: Spring AI `vector_store`(1536, HNSW, cosine, 자동 생성). 별도 벡터 테이블은 근거가 있을 때만
- **임베딩 모델·임계값·topK 를 바꾸면** `measurements.md` 를 읽고, 변경 후 같은 형식으로 측정 결과를 추가하도록 검증 계획에 넣는다. 모델 변경은 전체 재임베딩 계획 필요
- 가드레일은 LiteLLM 쪽(ADR 0002). 앱은 400 응답의 `GUARDRAIL_MARKERS` 로 구분만 한다

### Flowable 워크플로우
- 엔진 DB(5433)와 앱 DB(5432)는 **한 트랜잭션이 아니다.** 둘을 함께 바꾸면 순서와 보상을 명시(현재: `task_master` INSERT → 프로세스 시작)
- userTask id ↔ `WorkflowService.STEP_KEYS` ↔ REST `step` 파라미터 ↔ 화면 파일명이 1:1. 단계를 바꾸면 네 곳을 모두 분배 지시에 넣는다
- `taskId`(WF-…, 업무 식별자)와 `flowableTaskId`(엔진, 단계마다 새로 발급)를 구분해 API 에 적는다
- 완료 요청은 `step` 을 함께 받아 오래된 목록의 재완료를 거부
- 종료 조회는 `HistoryService` 기반 별도 엔드포인트(`STEP_KEYS` 에 넣지 않음)

### 프로젝트 / SCM
- 저장소 종류 추가는 `ScmClient` 구현체 빈 하나 + `supports(repoUrl)`. `RepoSyncService`·`RepoPushService` 는 손대지 않는 설계가 기본
- 쓰기는 `putFile` default 메서드(미지원 예외) — 조회 전용 구현체가 깨지지 않게
- 외부 HTTP 대기 중 DB 커넥션을 잡지 않도록 트랜잭션 없이 단위별 UPDATE, 개별 실패는 결과 항목 `FAILED`
- HTTP 클라이언트는 Spring `RestClient`. 경로 세그먼트는 `pathSegment` 로(슬래시 `%2F` 인코딩 404 회귀)
- 설정은 `application-local.yaml`·`application-e2e.yaml` **두 파일 모두**, 비밀값은 `.env`

## 작성 원칙

- 결정마다 근거 1줄 이상. 불확실하면 "확인 필요"
- 설정 변경은 두 프로파일 yaml 을 함께 고치도록 명시
- 의존성 추가가 필요하면 별도 섹션(Boot 4·Jackson 3 호환 여부 포함)

## 후속 작업

기존 문서를 먼저 읽고 피드백 대상 섹션만 수정, 상단에 변경 이력 추가.
