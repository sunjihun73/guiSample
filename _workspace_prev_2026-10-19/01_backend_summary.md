# 01. 백엔드 구현 요약 (T2 — spring-backend-engineer)

> 작성: spring-backend-engineer | 날짜: 2026-07-08
> 근거: `_workspace/00_architect_design.md` (§7 API / §8 트랜잭션) + `_workspace/02_data_summary.md`
> 상태: `./gradlew clean compileJava` BUILD SUCCESSFUL

## 1. 생성/변경 파일 목록

| 유형 | 경로 | 구분 |
|------|------|------|
| Service | `service/ChatService.java` | 신규 |
| Service | `service/AIService.java` | 변경(streamChat 추가) |
| Controller | `controller/user/AIRestController.java` | 변경(A/B/C 추가, D 확장) |
| DTO | `dto/SessionRow.java` | 신규 |
| DTO | `dto/SessionListResponse.java` | 신규 |
| DTO | `dto/SessionCreateResponse.java` | 신규 |
| DTO | `dto/MessageRow.java` | 신규 |
| DTO | `dto/MessageListResponse.java` | 신규 |
| Config | `application.yaml` | 변경 없음 — `stream-usage: true` 이미 설정됨 |

주입받은 Mapper(T1 제공, 손대지 않음): `ChatMasterMapper`, `ChatDetailMapper`.

## 2. 주요 빈 / 엔드포인트

- **ChatService** (`@Service`): `createSession(title)`, `selectSessions(param)`, `countSessions(param)`,
  `selectMessages(sessionId)`, `saveUserMessage(sessionId, content)`,
  `saveAssistantMessage(sessionId, content, model, promptTokens, completionTokens)`.
  - 저장/생성 메서드는 각각 독립 `@Transactional` 단건.
  - 상수: `DEFAULT_USER_ID = "sunjeehun"`, `DEFAULT_TITLE = "새로운채팅"`.
- **AIService** (`@Service`): `streamChat(query, categoryId): Flux<ChatResponse>` 신규(model/usage 확보),
  `getDocs(query, categoryId): Flux<String>` 는 streamChat 위에서 텍스트만 추출하도록 재구성(하위호환 유지),
  `static extractText(ChatResponse): String`(null-safe 델타 추출).
- **AIRestController** (`@RestController`, `@RequestMapping("/user/rag")`):
  A `GET /sessions`, B `POST /sessions`, C `GET /sessions/{sessionId}/messages`, D `POST /docs`(확장), 기존 `POST /embedding`.

## 3. REST 응답 JSON shape (프론트 계약 — 그대로 파싱)

### A. GET `/user/rag/sessions?page=1&rows=100` → 200 `application/json`
```json
{
  "page": 1,
  "total": 1,
  "records": 2,
  "rows": [
    { "sessionId": "9f1c...", "title": "새로운채팅", "createDate": "2026-07-08 10:11:12.0" }
  ]
}
```
- 필드 타입: `page`(int), `total`(int, 전체 페이지 수), `records`(int, 전체 건수), `rows`(array).
- rows[] : `sessionId`(string), `title`(string), `createDate`(string).
- 정렬: `create_date DESC`(최신 상단).
- rows 는 비어 있어도 `[]`(null 아님). 목록 없으면 `records=0, total=0, rows=[]`.

### B. POST `/user/rag/sessions` (body `{ "title": "..."}` 선택) → 200 `application/json`
성공:
```json
{ "success": true, "sessionId": "9f1c...", "title": "새로운채팅", "message": null }
```
실패:
```json
{ "success": false, "sessionId": null, "title": null, "message": "에러 메시지" }
```
- body 없음/`title` 공백 → 서버가 `"새로운채팅"` 기본값 적용.
- 필드 타입: `success`(boolean), `sessionId`(string|null), `title`(string|null), `message`(string|null).

### C. GET `/user/rag/sessions/{sessionId}/messages` → 200 `application/json`
```json
{
  "sessionId": "9f1c...",
  "rows": [
    { "role": "user", "content": "휴가 규정 알려줘", "model": null,
      "promptTokens": null, "completionTokens": null, "createDate": "2026-07-08 10:12:00.0" },
    { "role": "assistant", "content": "제공된 문서에...", "model": "gpt-4o-mini",
      "promptTokens": 812, "completionTokens": 143, "createDate": "2026-07-08 10:12:03.0" }
  ]
}
```
- 정렬: `chat_content_id ASC`(시간순).
- rows[] : `role`("user"|"assistant"), `content`(string), `model`(string|null),
  `promptTokens`(int|null), `completionTokens`(int|null), `createDate`(string).
- **표시 매핑 주의**: 프론트 CSS 는 `assistant` → `bot` 으로 변환해 렌더(설계 §9.5).
- `model`/`promptTokens`/`completionTokens` 는 user 행에서 항상 null, assistant 행에서 non-null(단 usage 미확보 시 토큰만 null 가능).

### D. POST `/user/rag/docs` → 200 `text/event-stream` (기존과 동일한 SSE 토큰 스트림)
요청 body:
```json
{ "query": "휴가 규정 알려줘", "categoryId": "cat-uuid(선택)", "sessionId": "9f1c...(선택)" }
```
- **응답 포맷: 변경 없음.** 기존 프론트 SSE 파서 그대로 재사용. `text/event-stream`, 각 답변 델타가 `data:` 이벤트로 전송(Spring WebFlux 가 `Flux<String>` 원소당 SSE `data:` 프레임 생성).
- `query` 공백/누락 → `"질문을 입력해 주세요."` 1건 스트리밍(저장 없음).
- `sessionId` 있으면: 스트림 시작 전 user 저장, 완료 후 assistant 저장(model=gpt-4o-mini, usage 확보 시 토큰). 저장은 서버가 처리 — **프론트는 저장 API 를 별도 호출하지 않는다.**
- `sessionId` 공백/누락 → 저장 건너뛰고 답변만 스트리밍(방어적). 정상 흐름에선 프론트가 항상 채운다(설계 §9.4-d).

## 4. 프론트(T3)에 전달할 주의점

1. **createDate 실제 포맷**: DTO 가 String 이고 JDBC `getString` 결과라 **`"2026-07-08 10:11:12.0"`** 처럼 소수부(`.0`)가 붙어 나올 수 있음(설계 문서 예시의 `"2026-07-08 10:11:12"` 와 다름). 그대로 노출하므로 프론트는 이 포맷을 기준으로 파싱/표시하거나, 표시용으로 `.` 이후를 절삭할 것.
2. **role 매핑**: 저장/조회 값은 `"user"`/`"assistant"`. CSS 클래스는 `assistant → bot` 변환 필요.
3. **D(/docs) 스트림 포맷은 불변** — 세션 기능 추가로 인한 프론트 SSE 파싱 변경 없음. payload 에 `sessionId` 만 추가.
4. rows 배열은 항상 존재(빈 배열 허용). null 체크는 rows[] 원소 필드(model/토큰) 중심으로.

## 5. 백엔드 구현 세부 (설계 준수 확인)

- 스트림 전체를 트랜잭션으로 감싸지 않음. user 저장 / assistant 저장 각각 독립 `@Transactional` 단건(§8.2).
- 블로킹 JDBC 저장은 `Schedulers.boundedElastic()` 오프로딩. `saveUser.thenMany(textStream).concatWith(saveAssistantTail)` 구성으로 user→스트림→assistant 순서 보장.
- `ChatResponse.getMetadata()` 에서 model/`Usage`(getPromptTokens/getCompletionTokens: Integer) 확보. 마지막 usage 청크에서 값 존재 시에만 덮어쓰기. usage null 이면 토큰 null 저장(fallback).
- 저장 실패는 `log.error` 만 남기고 스트림 정상 종료(부분 실패 정책 §8.2).
- 페이징: `GET /sessions` 는 `startRow`+`pageSize` 를 **항상 함께** 세팅(QA MINOR #1 반영 — page1→startRow=0).

## 6. Mapper 시그니처 요구사항

추가 요청 없음. T1 제공 시그니처(`selectChatMasterList`/`countChatMaster`/`insertChatMaster`,
`selectChatDetailList`/`insertChatDetail`)로 충분.

---

## 7. T6 — 세션 제목 첫 질문 자동 요약 변경 (후속)

> 상태: `./gradlew clean compileJava` BUILD SUCCESSFUL
> 근거: T5(data) 추가 Mapper — `updateChatMasterTitle(sessionId,title,updateUserId)`, `selectChatMaster(sessionId)`

### 7.1 변경 파일
| 유형 | 경로 | 변경 |
|------|------|------|
| Service | `service/AIService.java` | `summarizeTitle(question)` 추가(비스트리밍 요약), `@Slf4j` 부여 |
| Service | `service/ChatService.java` | `selectSession(sessionId)`, `autoRenameTitleIfDefault(sessionId,newTitle)` 추가 |
| Controller | `controller/user/AIRestController.java` | `/docs` 완료 tail 에 `autoRenameTitle(sessionId,query)` 배선(assistant 저장 직후) |

### 7.2 동작 규칙
1. **트리거 조건**: `POST /docs` 스트림 완료 후 tail(boundedElastic 오프로딩)에서만 수행. 세션의 현재 제목이 기본값 `"새로운채팅"`일 때만 1회 자동 변경. 컨트롤러가 `selectSession`으로 선(先) 확인 → 기본값 아니면 **LLM 호출 없이 종료**(수동 변경 제목 보호). 실제 갱신은 `autoRenameTitleIfDefault`가 트랜잭션 내에서 기본값을 **재확인** 후 반영(레이스 안전).
2. **요약 생성**: `AIService.summarizeTitle` — RAG 어드바이저/벡터 컨텍스트 없이 공유 `ChatClient`를 `.call()`(비스트리밍) 1회 호출. 프롬프트: "다음 질문을 15자 이내의 짧은 명사형 한국어 제목으로 요약하라. 따옴표·마침표 없이 제목만 출력하라. 질문: {질문}". 결과는 앞뒤 공백/따옴표(`" ' \``)/마침표 제거 후 varchar(200) 안전을 위해 200자 절삭.
3. **Fallback**: LLM 호출 예외 또는 빈/공백 응답 → 질문 앞 **20자 원문 절삭**(말줄임표 없이)으로 제목 설정.
4. **타이밍/영향 격리**: 제목 확인·요약·갱신 전 과정이 스트림 완료 tail에서 비동기 수행되어 SSE 응답 지연 없음. 스트림 실패 시엔 tail 미실행(기존 `concatWith` 정책과 일관). 모든 실패는 `log.error`만 남기고 채팅 스트림/응답에 영향 없음.
5. **SSE 스트림 포맷 불변**, 응답 필드 추가 없음. 프론트(T7)는 스트림 종료 후 `GET /sessions` 재조회로 변경된 제목 반영.

### 7.3 프론트(T7) 안내
- `/docs` 스트림 정상 종료(SSE 완료) 후 **`GET /user/rag/sessions` 재조회** → rows[].title 갱신분 반영. 제목 변경은 비동기라 스트림 종료 직후 수백 ms 내 반영될 수 있음(요약 LLM 호출 시간). 필요 시 약간의 지연 후 재조회 또는 재시도 권장.
- 자동 변경은 세션 최초 대화 1회에만 발생(이후/수동 변경 세션은 불변).

---

## 8. T8 — 벡터 검색을 어드바이저 체인 안으로 이동 (가드레일 우선) (후속)

> 상태: `./gradlew clean compileJava` BUILD SUCCESSFUL (2026-07-15)
> 배경: 기존 `AIService.streamChat()` 은 `vectorStore.similaritySearch()` 를 어드바이저 체인 **밖**에서
> 먼저 실행한 뒤 그 컨텍스트를 시스템 프롬프트에 넣고 가드 어드바이저를 호출했다. 그래서 unsafe 질문이어도
> 벡터 검색 비용이 이미 발생했다. 벡터 검색+컨텍스트 주입을 **가드 뒤 순서의 어드바이저 안**으로 옮겨,
> 가드레일을 통과한 질문에 대해서만 검색이 실행되도록 순서 계약을 order 값으로 보장한다.

### 8.1 변경/추가 파일
| 유형 | 경로 | 변경 |
|------|------|------|
| Advisor | `advisor/RagContextAdvisor.java` | **신규** — `CallAdvisor`+`StreamAdvisor`. 벡터 검색 후 시스템 메시지에 컨텍스트 주입 |
| Config | `config/SpringAiConfig.java` | `ragContextAdvisor` 빈 추가(VectorStore 주입, top-k 4, order `HIGHEST_PRECEDENCE+100`) |
| Service | `service/AIService.java` | `streamChat()` 리팩터 — 벡터 검색/SearchRequest/Filter/context join/SYSTEM_PROMPT/TOP_K/VectorStore 필드 제거, 어드바이저 체인으로 이동 |

### 8.2 어드바이저 체인 실행 순서 (order 값)
1. `KananaSafeGuardAdvisor` — `Ordered.HIGHEST_PRECEDENCE` (가장 먼저). unsafe 면 체인 단락 → 벡터 검색·gpt-4o-mini 미호출.
2. `RagContextAdvisor` — `Ordered.HIGHEST_PRECEDENCE + 100` (가드 뒤). 가드 통과 시에만 `vectorStore.similaritySearch()` 실행 → 문서 본문을 `\n\n---\n\n` join(없으면 `"(관련 문서가 없습니다.)"`) → 시스템 프롬프트 `{context}` 자리에 **리터럴 치환**(`String.replace`, 문서 중괄호 안전) → `Prompt.augmentSystemMessage(...)` + `ChatClientRequest.mutate()` 로 시스템 메시지만 증강해 다음으로 전달. **user 메시지 원문 불변**(가드가 본 질문과 동일).

### 8.3 category_id 전달 방식
- `streamChat()` 이 `.advisors(spec -> { if (categoryId 비어있지 않음) spec.param("category_id", categoryId); })` 로 어드바이저 파라미터에 전달. (Spring AI `AdvisorSpec.param` 은 value null 을 거부하므로 값이 있을 때만 세팅.)
- 파라미터는 프레임워크가 `ChatClientRequest.context()` 로 전파. `RagContextAdvisor` 는 `request.context().get("category_id")` 로 읽어 값이 있으면 `FilterExpressionBuilder().eq("category_id", ...)` 를 `SearchRequest` 에 적용, null/공백이면 전체 검색.

### 8.4 불변 사항 / 회귀 없음
- `streamChat(query, categoryId): Flux<ChatResponse>` 시그니처·반환 형태 유지 → `getDocs()`/`AIRestController`/`ChatService` 저장 경로 **하위호환**.
- SSE `/docs` 응답 포맷 불변, REST 응답 shape(§3) 불변 → 프론트(T3/T7) 변경 없음.
- `summarizeTitle()` 은 손대지 않음 — 가드/RAG 어드바이저 미적용(순수 요약)이 의도된 설계.
- 스트리밍 경로의 블로킹 `similaritySearch` 는 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()).flatMapMany(chain::nextStream)` 로 오프로딩(KananaSafeGuardAdvisor.adviseStream 과 동일 스타일).
- Spring AI **2.0.0-M4** 실 API 확인 사용: `ChatClientRequest.mutate()/context()`, `Prompt.augmentSystemMessage(String)`, `Prompt.getUserMessage()`.
