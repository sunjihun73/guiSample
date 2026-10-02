# 00. RAG 채팅 세션 기능 설계 — rag.jsp 세션 목록 + chat_master/chat_detail 대화 저장

> 작성: rag-architect | 날짜: 2026-07-08
> 대상 스택: Spring Boot 4.0.6 / Spring AI BOM 2.0.0-M4 / pgvector / MyBatis 4.0.1 / JSP(JSTL) / Java 21 toolchain
> 패키지 루트: `kr.co.jihun.guisample`

## 변경 이력
| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-07-08 | 채팅 세션 기능 신규 설계 (세션 목록 UI + 대화 저장) | 사용자 요구 |
| 2026-07-08 (개정) | **세션 테이블을 project_master 재사용 → 신규 전용 `chat_master`로 교체.** ProjectMasterService/Mapper 재사용 폐기, ChatMaster 전용 계층 신설. project_master 이중 용도 충돌 해소. | 사용자 스키마 정정 |
| (이전) | 파일 업로드 인덱싱 / 지식파일 관리 — `_workspace_prev_2026-07-08/` 참조 | - |

---

## 0. 선행 확인 결과 (기존 코드/의존성 파악)

- **의존성:** `build.gradle`에 Spring AI(`spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-pgvector`), MyBatis(`mybatis-spring-boot-starter:4.0.1`), postgresql 드라이버 존재. **추가 의존성 불필요.** 세션/메시지 저장은 순수 MyBatis + 기존 pgvector 검색으로 충분.
- **기존 RAG 경로:** `AIRestController`(`@RequestMapping("/user/rag")`) → `AIService.getDocs(query, categoryId)` → `Flux<String>` SSE 스트리밍. `ChatClient`(gpt-4o-mini) + `VectorStore`(top-k=4, cosine). **이 경로가 대화 저장 로직의 삽입 지점이다.**
- **기존 화면:** `rag.jsp` — 좌측 `.rag-cat-panel`(카테고리, `flex:0 0 20%`) + 우측 `.chat-window`. 순수 vanilla JS(외부 rag.js 없음), 카테고리 로딩·SSE 스트리밍 IIFE 3개.
- **MyBatis 패턴:** `XxxDTO`(Lombok `@Getter/@Setter`) + `XxxMapper`(`@Mapper` 인터페이스) + `resources/mapper/XxxMapper.xml`(resultMap + `<sql>` 조각). `HashMap<String,Object> param` 페이징(`startRow`/`pageSize`) 관용구.
- **사용자 식별 체계:** 기존 코드에 인증/로그인 없음. `ProjectMasterService.DEFAULT_USER_ID = "sunjeehun"` 고정값 관용구가 있음. → 세션 소유자(`chat_owner_user_id`)와 감사 컬럼(create/update_user_id)은 **동일 고정값 `"sunjeehun"`** 사용, 향후 인증 연동 지점으로 명시.
- **세션 테이블 = 신규 전용 `chat_master`** (개정). project_master는 이제 채팅과 무관 — 이중 용도 충돌 없음.

---

## 1. 목표와 범위

### 사용자 시나리오
1. 사용자가 `/user/rag` 진입 → 좌측 최외곽에 **"채팅세션" 목록 패널**이 보인다(카테고리 패널보다 좁게, 그 왼쪽에 위치).
2. **"새 채팅" 버튼** 클릭 → 새 세션이 즉시 생성되고, 목록 상단에 **"새로운채팅"** 항목이 추가되며, 채팅창이 초기화(인사 메시지만 남김)된다.
3. 세션 목록이 **비어있는 상태**에서 사용자가 바로 메시지를 보내면 → **"새로운채팅"** 세션이 자동 생성되고 그 세션에 대화가 귀속된다.
4. 사용자가 메시지를 주고받으면 **user/assistant 모든 발화가 `chat_detail`에 저장**된다(모델명·토큰 수 포함).
5. 목록에서 세션 클릭 → 해당 세션의 이전 대화가 채팅창에 복원된다.

### 비범위 (Non-goals)
- 세션 이름 변경(rename)/삭제/검색, 첫 메시지 기반 자동 제목 생성 — 이번 범위 제외(이름은 "새로운채팅" 고정).
- 대화 메모리를 LLM 프롬프트에 주입하는 멀티턴 컨텍스트(`MessageChatMemoryAdvisor`) — **이번 범위 제외.** 저장은 하되, 답변 생성은 기존과 동일하게 "현재 질문 + 벡터 검색 컨텍스트"만 사용(단일 턴).
- 사용자 인증/멀티유저 격리 — 소유자·감사 컬럼은 고정값 `sunjeehun`. 세션은 전역 공유.

---

## 2. 데이터 모델

두 테이블 모두 **신규**. DDL은 파일로만 작성하고 실행은 사용자 승인 후(원칙 유지).
DDL 파일 위치: `src/main/resources/db/chat_schema.sql` (chat_master + chat_detail 두 CREATE 모두 포함, 상단 주석에 "사용자 승인 후 수동 적용" 명시).

### 2.1 채팅 세션 테이블 = `public.chat_master` (신규, 사용자 정정 DDL 그대로)

| 컬럼 | 타입 | 매핑 필드 | 설명 |
|------|------|-----------|------|
| `chat_session_id` | varchar(100) PK | `chatSessionId` (String) | 세션 ID — `UUID.randomUUID().toString()`(기존 project 생성 관용구와 동일) |
| `chat_title_name` | varchar(200) NOT NULL | `chatTitleName` (String) | 세션 제목 — 신규 세션 = `"새로운채팅"` |
| `chat_owner_user_id` | varchar(50) NOT NULL | `chatOwnerUserId` (String) | 소유자 — 고정값 `"sunjeehun"`(인증 도입 시 대체) |
| `update_user_id` | varchar(100) | `updateUserId` (String) | 고정값 `"sunjeehun"` |
| `update_date` | timestamp DEFAULT NOW | `updateDate` (String) | 수정 시각 |
| `create_user_id` | varchar(100) | `createUserId` (String) | 고정값 `"sunjeehun"` |
| `create_date` | timestamp DEFAULT NOW | `createDate` (String) | 생성 시각 — 목록 정렬 키(`create_date DESC` → 최신 상단) |

- **신규 전용 계층:** `ChatMasterDTO / ChatMasterMapper / ChatMasterMapper.xml`. project_master 계층은 **재사용하지 않는다.**
- 세션 생성 규칙: 서비스에서 `chatSessionId = UUID.randomUUID().toString()`, `chatTitleName = "새로운채팅"`, `chatOwnerUserId/create_user_id/update_user_id = "sunjeehun"`, 날짜는 mapper의 `NOW()`.

### 2.2 채팅 메시지 테이블 = `public.chat_detail` (신규, 최초 제공 DDL 그대로 유지)

| 컬럼 | 타입 | 매핑 필드 | 설명 |
|------|------|-----------|------|
| `chat_content_id` | BIGSERIAL PK | `chatContentId` (Long) | 메시지 고유 ID(자동 증가) |
| `chat_session_id` | varchar(100) NOT NULL | `chatSessionId` (String) | = chat_master.chat_session_id (논리 FK, 물리 FK는 두지 않음) |
| `chat_content` | text | `chatContent` (String) | 발화 본문 |
| `role` | varchar(20) NOT NULL | `role` (String) | `"user"` \| `"assistant"` |
| `model` | varchar(100) | `model` (String) | assistant 행만: `gpt-4o-mini` |
| `prompt_tokens` | int | `promptTokens` (Integer) | assistant 행만(스트리밍 usage) |
| `completion_tokens` | int | `completionTokens` (Integer) | assistant 행만 |
| `update_user_id` / `update_date` | varchar / timestamp DEFAULT NOW | `updateUserId` / `updateDate` | 고정값 / NOW() |
| `create_user_id` / `create_date` | varchar / timestamp DEFAULT NOW | `createUserId` / `createDate` | 고정값 / NOW() |

- **인덱스 권장(DDL에 포함):** `CREATE INDEX idx_chat_detail_session ON chat_detail (chat_session_id, chat_content_id);` — 세션별 대화 조회(ORDER BY chat_content_id ASC)의 커버링.
- **정렬 기준:** `chat_content_id ASC`(BIGSERIAL 단조 증가) → 삽입 순서 보장.

### 벡터 테이블 (`vector_store`) — 변경 없음

---

## 3. 인덱싱 파이프라인 — 변경 없음
문서 인덱싱(청킹/임베딩/적재)은 기존 `KnowledgeFileService`/`EmbeddingService` 유지. 본 기능과 무관.

---

## 4. 검색 파이프라인 — 변경 없음
`AIService.getDocs` 유지: top-k=4, cosine(HNSW 일치), `category_id` 메타 필터, rerank 미사용. 세션 기능은 검색 파라미터를 바꾸지 않는다(멀티턴 컨텍스트 미주입 — 1절 비범위).

---

## 5. 프롬프트 / Advisor / 대화 저장 구성

### 5.1 프롬프트 — 변경 없음
`AIService.SYSTEM_PROMPT`(사내 문서 도우미, context 슬롯) 그대로.

### 5.2 대화 저장을 위한 스트리밍 경로 개편 (핵심 백엔드 변경)
현재 `getDocs`는 `Flux<String>`(토큰 텍스트)만 반환 → **model/token usage를 알 수 없다.** 저장 요구를 만족시키려면 스트림에서 (a) assistant 전체 텍스트, (b) model, (c) usage를 확보해야 한다.

**설계 결정:** `ChatClient` 스트림을 `.content()`가 아니라 **`.chatResponse()`(`Flux<ChatResponse>`)** 로 소비한다.
- 각 `ChatResponse`에서 `getResult().getOutput().getText()`를 뽑아 **SSE로 클라이언트에 그대로 스트리밍**(기존 프론트 파서 호환 유지).
- 서버는 토큰을 누적(`StringBuilder`)하고, **마지막 청크의 `ChatResponse.getMetadata()`에서 model과 `Usage`(promptTokens/completionTokens)** 를 획득.
- 스트림 완료 시(`doOnComplete`/`concatWith`) `ChatService`로 **assistant 메시지 1건 저장**.

**⚠ 확인 필요 — 스트리밍 usage 활성화:** OpenAI 스트리밍에서 usage를 받으려면 `stream_options.include_usage=true`가 필요하다. Spring AI OpenAI에서는 `spring.ai.openai.chat.options.stream-usage: true`(또는 `OpenAiChatOptions.streamUsage(true)`)로 설정 → 마지막 청크에 집계 usage 포함. **application.yaml에 `spring.ai.openai.chat.options.stream-usage: true` 추가 필요.** usage가 여전히 null이면 `prompt_tokens/completion_tokens`는 **null 저장**(컬럼 nullable)하고 model만 기록 — 저장 실패로 이어지지 않게 fallback.

**멀티턴 미도입 근거:** 저장만 요구되었고, 과거 대화를 프롬프트에 넣으면 검색 컨텍스트·비용·정확도에 영향. 초기 버전은 단일 턴 유지(명확성 우선). 향후 `MessageChatMemoryAdvisor` 도입은 변경 이력으로 진화.

---

## 6. 충돌 / 결정 요청

- **[해소됨] project_master 이중 용도 충돌** — 세션을 신규 전용 `chat_master`로 분리하여 해소. project_master는 프로젝트 관리 전용으로 유지, 채팅과 무관.
- 남은 확인 사항은 12절 참조(DDL 실행 승인, 스트리밍 usage 프로퍼티).

---

## 7. API 스펙 (요청/응답 JSON shape 확정)

기존 `AIRestController`(`/user/rag`)에 세션·메시지 엔드포인트를 추가한다.

| # | Method | Path | 요청 | 응답 | 설명 |
|---|--------|------|------|------|------|
| A | GET  | `/user/rag/sessions` | `?page=1&rows=100` | `SessionListResponse` | 세션 목록(chat_master) |
| B | POST | `/user/rag/sessions` | `{ "title": "새로운채팅" }`(선택) | `SessionCreateResponse` | 새 세션 생성 |
| C | GET  | `/user/rag/sessions/{sessionId}/messages` | - | `MessageListResponse` | 세션 대화 복원 |
| D | POST | `/user/rag/docs` | `{ "query","categoryId?","sessionId" }` | `text/event-stream` | RAG 답변 스트리밍 + 대화 저장(**기존 확장**) |

### A. GET /user/rag/sessions → SessionListResponse
```json
{ "page":1, "total":1, "records":2,
  "rows":[
    { "sessionId":"9f1c…", "title":"새로운채팅", "createDate":"2026-07-08 10:11:12" }
  ] }
```
- 백엔드: `ChatMasterMapper.selectChatMasterList`/`countChatMaster`. `chatSessionId→sessionId`, `chatTitleName→title` 매핑(컨트롤러 매핑 or 응답 record 변환). 정렬 `create_date DESC` → 최신 세션 상단.

### B. POST /user/rag/sessions → SessionCreateResponse
- 요청 body 없거나 `title` 공백 → 서버가 `"새로운채팅"` 기본값.
```json
{ "success":true, "sessionId":"9f1c…", "title":"새로운채팅" }
```
- 백엔드: `ChatService.createSession(title)` → UUID 생성 + owner/user_id 고정 + NOW() → chat_master insert.

### C. GET /user/rag/sessions/{sessionId}/messages → MessageListResponse
```json
{ "sessionId":"9f1c…",
  "rows":[
    { "role":"user",      "content":"휴가 규정 알려줘", "createDate":"2026-07-08 10:12:00" },
    { "role":"assistant", "content":"제공된 문서에…",   "model":"gpt-4o-mini",
      "promptTokens":812, "completionTokens":143, "createDate":"2026-07-08 10:12:03" }
  ] }
```
- 정렬 `chat_content_id ASC`. `chatContent→content` 매핑.

### D. POST /user/rag/docs (기존 확장 — SSE, 저장 추가)
- 요청 body에 **`sessionId` 추가**(프론트가 항상 채워 보냄 — 9절 흐름 참조). 기존 `query`,`categoryId?` 유지.
```json
{ "query":"휴가 규정 알려줘", "categoryId":"cat-uuid(선택)", "sessionId":"9f1c…" }
```
- 응답: 기존과 **동일한 `text/event-stream` 토큰 스트림**(프론트 SSE 파서 그대로 재사용 — 하위호환).
- 서버 동작(트랜잭션 경계는 8절):
  1. `sessionId` 유효 시 **user 메시지 저장**(role=user) — 스트림 시작 전.
  2. 답변 토큰 스트리밍(클라이언트 전송) + 서버 누적.
  3. 스트림 완료 후 **assistant 메시지 저장**(role=assistant, model, tokens).
- `sessionId` 공백/누락 시(예외적): 저장은 건너뛰고 답변만 스트리밍(방어적). 정상 흐름에선 프론트가 항상 채운다.

### DTO 정의 (신규)
- `dto/ChatMasterDTO` (Lombok): `String chatSessionId; String chatTitleName; String chatOwnerUserId; String updateUserId; String updateDate; String createUserId; String createDate;`
- `dto/ChatDetailDTO` (Lombok): `Long chatContentId; String chatSessionId; String chatContent; String role; String model; Integer promptTokens; Integer completionTokens; String updateUserId; String updateDate; String createUserId; String createDate;`
- 응답 wrapper는 기존 관용구대로 컨트롤러에서 `Map<String,Object>` 또는 전용 record 사용(팀 재량, shape는 위 JSON 고정). 세션 목록 rows 매핑용 경량 record `SessionRow(String sessionId,String title,String createDate)` 권장.

---

## 8. Service 트랜잭션 경계 & 저장 흐름 (백엔드 핵심)

### 8.1 신규 `ChatService` (세션 + 메시지 통합 비즈니스 계층)
데이터 계층은 `ChatMasterMapper` + `ChatDetailMapper` 2개, 비즈니스는 `ChatService` 하나로 통합(클래스 스프롤 최소화).
- `createSession(title)` → `ChatMasterDTO` — `@Transactional`, UUID/owner/user_id 세팅 후 chat_master insert. title 공백 시 "새로운채팅".
- `selectSessions(param)` / `countSessions(param)` — 목록/카운트.
- `saveUserMessage(sessionId, content)` — `@Transactional` 단건 insert(role=user). 스트림 시작 **전** 동기 커밋.
- `saveAssistantMessage(sessionId, content, model, promptTokens, completionTokens)` — `@Transactional` 단건 insert(role=assistant). 스트림 **완료 후** 커밋.
- `selectMessages(sessionId)` → `List<ChatDetailDTO>`.
- user_id/owner 고정값(`sunjeehun`), 날짜 NOW()는 mapper XML에서 처리(기존 project 패턴 동일). 상수 `DEFAULT_USER_ID` 재정의(또는 공용화).

### 8.2 트랜잭션 경계 원칙 (스트리밍 특성 반영)
- **스트림 전체를 하나의 트랜잭션으로 감싸지 않는다.** 리액티브 `Flux` 수명 동안 JDBC 커넥션/트랜잭션을 잡으면 커넥션 고갈. → **user 저장 / assistant 저장을 각각 독립된 짧은 트랜잭션**으로 분리.
- **블로킹 JDBC를 리액티브 스트림 안에서 호출 시** `Schedulers.boundedElastic()`으로 오프로딩(예: `doOnComplete`에서 `Mono.fromRunnable(save).subscribeOn(boundedElastic)`). MyBatis는 블로킹이므로 이벤트 루프 점유 금지. **(중요 구현 지침)**
- 세션 자동 생성(요구4)은 **프론트 선행 호출(B)** 로 처리 → `/docs` 서버는 항상 존재하는 sessionId를 받는다(백엔드 원자성 단순화).
- **부분 실패 정책:** assistant 저장 실패해도 답변은 이미 스트리밍 완료 → 저장 실패는 **에러 로그만** 남기고 스트림 정상 종료(사용자 경험 우선). user 저장 실패 시에도 답변 스트리밍 계속(로그).

### 8.3 AIService 변경
- 신규 메서드 `streamChat(query, categoryId)` → `Flux<ChatResponse>`(내부는 기존 검색 + `.chatResponse()` 스트림). 기존 `getDocs`는 유지하거나 `streamChat` 위에 `.map(text)`로 재구성(팀 재량, 시그니처 문서화 필수).
- 컨트롤러 D는 `streamChat` 구독 → text 추출 SSE 전송 + 누적/완료 시 `ChatService.saveAssistantMessage`.

---

## 9. UI 시나리오 (레이아웃 · 이벤트 흐름)

### 9.1 라우트 — 변경 없음
`GET /user/rag` → `WEB-INF/views/user/rag.jsp` (MainController 기존).

### 9.2 레이아웃 (rag.jsp `.rag-body` 3단 구성)
현재: `[.rag-cat-panel(20%)] [.chat-window]` → 변경: **`[.rag-session-panel] [.rag-cat-panel(20%)] [.chat-window]`**
- **세션 패널(신규, 최좌측):** 카테고리 패널보다 좁게. 예 `flex: 0 0 15%`(카테고리 20% > 세션 15%). 근거: 요구2 "현재 카테고리 목록보다 조금 더 좁게".
- 패널 구조(카테고리 패널 마크업/클래스 관용구 재사용):
  - 상단 헤더 행: 좌측 제목 **"채팅세션"**(`.rag-session-panel__title`), 우측 **"새 채팅" 버튼**(`.rag-session-newbtn`) — flex space-between.
  - `#ragSessionList`(`.rag-session-list`) — 세션 항목 세로 목록. 각 항목 `.rag-session-item`(선택 시 `.is-selected`), `textContent`=title(XSS 방지), `dataset.sessionId`.
  - `#ragSessionMsg` 로딩/빈 상태 메시지(비었을 때 "채팅을 시작하면 세션이 생성됩니다.").
- CSS는 `common.css`에 `.rag-session-*` 규칙 추가(기존 `.rag-cat-*` 스타일 미러링). 신규 클래스 네이밍으로 카테고리와 분리.

### 9.3 클라이언트 상태
- `currentSessionId`(String|null): 현재 활성 세션. 초기 null.

### 9.4 이벤트 흐름
**(a) 페이지 로드:** GET A로 세션 목록 로딩 → 렌더. 목록이 있으면 **최상단(최신) 세션을 자동 활성화**하고 GET C로 대화 복원(권장). 비었으면 `currentSessionId=null`, 채팅창은 기본 인사만.

**(b) "새 채팅" 클릭(요구3):**
1. POST B(`{}`) → `{sessionId,title}` 수신.
2. 목록 **최상단에 항목 추가** + 즉시 선택(`.is-selected`).
3. `currentSessionId = sessionId`.
4. **채팅창 초기화**(메시지 영역을 기본 인사 1건으로 리셋), 입력창 포커스.

**(c) 세션 항목 클릭:** `currentSessionId` 갱신 + 선택 표시 이동 → GET C로 대화 복원(user/assistant 말풍선 재생, 기존 `appendMessage(role,text)` 재사용, `assistant`→`bot` 매핑 주의). 스트리밍 커서 없이 정적 렌더.

**(d) 메시지 전송(요구4 자동생성 포함):**
1. 입력값 확보. `if (!currentSessionId)` → **POST B 선행 호출**로 "새로운채팅" 생성 → 목록 상단 추가·선택·`currentSessionId` 설정(요구4 충족).
2. user 말풍선 추가(기존 로직).
3. `streamAnswer(query)` payload에 **`sessionId: currentSessionId` 추가**(+ 기존 categoryId). 나머지 SSE 소비 로직 그대로.
4. 스트리밍 답변 표시(기존). 저장은 서버가 담당(프론트는 저장 API 직접 호출 안 함).

### 9.5 표시 규칙
- 역할 매핑: 저장/조회 시 role 값은 `"user"`/`"assistant"`. 프론트 CSS 클래스는 기존 `chat-msg--user`/`chat-msg--bot` → **`assistant`는 `bot`으로 변환**해 렌더.
- 모든 텍스트 `textContent` 삽입(XSS 방지) — 기존 관용구 유지.
- 토큰/모델 정보는 이번 UI에 노출하지 않음(저장만). 향후 표시 가능.

---

## 10. 의존성 / 설정 변경
- **build.gradle: 추가 없음.**
- **application.yaml: 1줄 추가 권장** — `spring.ai.openai.chat.options.stream-usage: true` (스트리밍 토큰 usage 확보용, 5.2 참조). 미설정 시 토큰 null 저장으로 동작은 유지.

---

## 11. 작업 분배 (T1 data / T2 backend / T3 frontend / T4 qa)

### T1 — mybatis-data-engineer (선행, T2 blocker)
1. `src/main/resources/db/chat_schema.sql` **DDL 파일 작성** — **chat_master + chat_detail 두 CREATE 모두 포함** + `idx_chat_detail_session` 인덱스. **실행하지 말 것**(파일만). 상단 주석에 "사용자 승인 후 수동 적용" 명시. (사용자 제공 정정 DDL을 그대로 사용.)
2. `dto/ChatMasterDTO`, `dto/ChatDetailDTO` 생성(7절 필드, Lombok).
3. `mapper/ChatMasterMapper`(@Mapper): `insertChatMaster(ChatMasterDTO)`, `selectChatMasterList(HashMap param)`, `countChatMaster(HashMap param)`.
4. `mapper/ChatDetailMapper`(@Mapper): `insertChatDetail(ChatDetailDTO)`, `selectChatDetailList(String sessionId)`.
5. `resources/mapper/ChatMasterMapper.xml` / `ChatDetailMapper.xml`: resultMap(snake→camel), insert(owner/user_id 파라미터, create/update_date `NOW()`), 목록 select(chat_master는 `create_date DESC` + 페이징 `LIMIT #{pageSize} OFFSET #{startRow}`; chat_detail는 `WHERE chat_session_id=#{sessionId} ORDER BY chat_content_id ASC`).
6. **project_master 계층은 손대지 말 것**(채팅과 무관, 재사용 폐기).

### T2 — spring-backend-engineer (T1 완료 후)
1. `service/ChatService`(8.1): createSession / selectSessions / countSessions / saveUserMessage / saveAssistantMessage / selectMessages. 저장 메서드는 각 `@Transactional` 단건. owner/user_id 고정값 `"sunjeehun"`.
2. `AIService.streamChat(query, categoryId): Flux<ChatResponse>` 추가(5.2) — `.chatResponse()` 스트림, model/usage 추출 가능 형태.
3. `AIRestController` 확장:
   - A `GET /sessions`(ChatService.selectSessions/countSessions, chatSessionId/chatTitleName→sessionId/title 매핑)
   - B `POST /sessions`(ChatService.createSession, title 기본 "새로운채팅")
   - C `GET /sessions/{sessionId}/messages`(ChatService.selectMessages)
   - D `POST /docs` 확장: body에서 `sessionId` 파싱 → user 저장(스트림 전) → `streamChat` SSE 전송 + 누적 → 완료 시 assistant 저장. 블로킹 저장은 `boundedElastic` 오프로딩(8.2). 저장 실패는 로그만.
4. `application.yaml`에 `stream-usage: true` 추가(10절).
5. 응답 JSON shape는 7절 고정(프론트 계약).

### T3 — jsp-frontend-engineer (T2 API 확정 후)
1. `rag.jsp` `.rag-body`에 **세션 패널을 카테고리 패널 왼쪽에 신규 추가**(9.2 마크업): 제목 "채팅세션" + "새 채팅" 버튼 + `#ragSessionList` + `#ragSessionMsg`.
2. `common.css`에 `.rag-session-panel`(카테고리보다 좁은 flex 폭, 예 `flex:0 0 15%`) / `.rag-session-panel__title` / `.rag-session-newbtn` / `.rag-session-list` / `.rag-session-item(.is-selected)` 규칙 추가(기존 `.rag-cat-*` 미러링).
3. JS: `currentSessionId` 상태 + 함수 — `loadSessions()`(A), `createSession()`(B, 목록 상단 prepend+선택), `loadMessages(sessionId)`(C, `assistant→bot` 매핑 렌더), `resetChatWindow()`.
4. 이벤트 배선(9.4): "새 채팅" 클릭 → createSession+채팅창 초기화; 항목 클릭 → 활성화+복원; 전송 시 `if(!currentSessionId) await createSession()`(요구4) 후 payload에 `sessionId` 포함.
5. 기존 SSE 스트리밍/카테고리 IIFE는 최대한 재사용, `streamAnswer` payload에 `sessionId`만 추가. XSS `textContent` 유지.

### T4 — integration-qa (T2·T3 완료 후, DDL 적용 확인 필요)
1. **선결:** `chat_master`·`chat_detail` 테이블 존재 확인(미존재 시 사용자에게 DDL 적용 요청 — QA는 실행하지 않음).
2. `bootRun` → `/user/rag` 진입 → 세션 패널 렌더/폭이 카테고리보다 좁음 확인.
3. "새 채팅" → 목록 상단 "새로운채팅" 추가 + 채팅창 초기화 확인. DB `chat_master`에 행 생성(owner/user_id="sunjeehun") 확인.
4. 빈 목록 상태에서 바로 메시지 전송 → "새로운채팅" 자동 생성(요구4) + 답변 확인.
5. 대화 후 DB `chat_detail`: user/assistant 2행, `chat_session_id` 일치, assistant `model=gpt-4o-mini`, 토큰(활성 시 non-null) 확인.
6. 세션 클릭 → 이전 대화 복원(순서 chat_content_id ASC) 확인.
7. 회귀: 기존 `/user/rag/docs` 스트리밍·카테고리 필터, `projects.jsp` 정상 동작 확인(이제 세션과 무관하게 독립).

---

## 12. 확인 필요(요약)
1. **DDL 실행** — `chat_schema.sql`(chat_master + chat_detail) 파일만 작성, 실제 CREATE는 사용자 승인/수동 적용.
2. **스트리밍 usage 프로퍼티(5.2)** — `spring.ai.openai.chat.options.stream-usage` 키/집계 청크 위치는 백엔드 구현 시 로그 검증. null이면 토큰 null 저장 fallback.
