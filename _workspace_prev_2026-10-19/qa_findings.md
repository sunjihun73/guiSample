# QA Findings — RAG 채팅 세션 기능

## 2026-07-08 라운드 1 — T1 (데이터 계층) 점진 검증

검증자: integration-qa | 대상: T1 산출물 7개 파일 | 기준: `00_architect_design.md`, `02_data_summary.md`

심각도 요약: **BLOCKER 0 / MAJOR 0 / MINOR 2 / PASS 4개 경계면**

### [PASS] 경계면 2 — ResultMap ↔ DTO (shape 일치)
- `ChatMasterMapper.xml` `chatMasterResultMap` 7개 property ↔ `ChatMasterDTO` 7개 필드 완전 일치. `<id>`=chatSessionId 정확.
- `ChatDetailMapper.xml` `chatDetailResultMap` 11개 property ↔ `ChatDetailDTO` 11개 필드 완전 일치. 타입 호환: chatContentId Long↔BIGSERIAL, promptTokens/completionTokens Integer↔int. `<id>`=chatContentId 정확.

### [PASS] 경계면 3 — Mapper interface ↔ XML statement
- ChatMasterMapper 3개 메서드 ↔ XML statement 3개 1:1 매칭, parameterType·resultMap 일치. insert `#{}` 5개 모두 DTO 필드 존재, 날짜는 `NOW()`.
- ChatDetailMapper 2개 메서드 ↔ XML 2개 매칭. `@Param("sessionId")` ↔ `#{sessionId}` 정확. insert `#{}` 8개 모두 DTO 필드 존재. chat_content_id(BIGSERIAL)는 insert 제외 — 자동증가 정합.

### [PASS] SQL 컬럼 ↔ 확정 DDL(chat_schema.sql)
- 두 XML의 모든 INSERT/SELECT 컬럼이 chat_master(7컬럼)/chat_detail(11컬럼) DDL과 완전 일치. PK/인덱스(`idx_chat_detail_session (chat_session_id, chat_content_id)`) 정합. `IF NOT EXISTS` 멱등.

### [PASS] 세션 생성 규칙(설계 4번) ↔ 데이터 계층 가정
- UUID/"새로운채팅"/"sunjeehun"은 서비스(T2)가 DTO에 세팅, 날짜는 mapper `NOW()` — 모순 없음. NOT NULL 제약(chat_owner_user_id/chat_title_name)은 서비스가 항상 세팅해야 함 → T2 라운드에서 재확인.

### [MINOR-1] 페이징 가드 비대칭 (`ChatMasterMapper.xml` LIMIT/OFFSET)
- `<if test="pageSize != null">`가 pageSize만 검사하는데 본문은 `#{startRow}`도 참조. T2가 pageSize만 넣고 startRow 누락 시 `OFFSET null` 런타임 오류.
- 조치: T2 서비스가 pageSize 세팅 시 startRow(page 1 → 0)를 항상 함께 세팅하는지 라운드 2 확인. 또는 가드를 `pageSize != null and startRow != null`로 강화.

### [MINOR-2] timestamp → String 매핑 포맷
- updateDate/createDate DTO String ↔ DB timestamp (기존 프로젝트 관용구). JDBC getString 포맷(`2026-07-08 10:11:12.0`)이 API 설계 예시(`"2026-07-08 10:11:12"`)와 미세 차이 가능 → 노출 포맷 정리를 T2/T3 라운드에서 확인.

### 배선(runtime binding) 확인
- `@MapperScan("kr.co.jihun.guisample.mapper")` + `mapper-locations=classpath:mapper/**/*.xml` → 신규 Mapper/XML 모두 로드. namespace=interface FQN 일치. BLOCKER 없음.

### 결론
T1 데이터 계층 정합. MINOR 2건은 T2 검증 라운드로 이월.

---

## 2026-07-08 라운드 2 — T2 (백엔드) 검증

검증자: integration-qa | 대상: ChatService/AIService/AIRestController + 5개 응답 DTO | 기준: `00_architect_design.md §7·§8`, `01_backend_summary.md`

심각도 요약: **BLOCKER 0 / MAJOR 0 / MINOR 2 신규 + 이월 2건 처리** · `./gradlew compileJava` BUILD SUCCESSFUL

### [PASS] REST 응답 shape ↔ 계약(§7 / backend_summary §3) — DTO 필드 실측
- **A GET /sessions** → `SessionListResponse{ page:int, total:int(전체 페이지 수), records:int(전체 건수), rows:List<SessionRow> }`, `SessionRow{ sessionId, title, createDate }`. 컨트롤러 `sessions()`가 `ChatMasterDTO.chatSessionId→sessionId, chatTitleName→title, createDate→createDate`로 정확 매핑(AIRestController:76-82). rows는 `.collect(toList())`로 항상 비어도 `[]`. 계약 일치. ✔
- **B POST /sessions** → `SessionCreateResponse{ success:boolean, sessionId, title, message }`. 성공: success=true+sessionId+title, message=null. 실패: success=false+message, sessionId/title=null (컨트롤러 105-122). 계약 일치. ✔
- **C GET /sessions/{sessionId}/messages** → `MessageListResponse{ sessionId, rows:List<MessageRow> }`, `MessageRow{ role, content, model, promptTokens(Integer), completionTokens(Integer), createDate }`. `ChatDetailDTO.chatContent→content` 매핑 정확(컨트롤러 139-148). user행 model/토큰 null, assistant행 non-null 계약 반영. ✔
- **D POST /docs** → `Flux<String>` `text/event-stream`. 응답 포맷 불변(하위호환), payload에 sessionId 추가 파싱. 계약 일치. ✔
- 경로: 클래스 `@RequestMapping("/user/rag")` + 메서드 `/sessions`·`/sessions/{sessionId}/messages`·`/docs` → 전체 URL이 설계 §7 표와 일치. HTTP 메서드(GET/POST) 일치. ✔ (라운드 3에서 JSP/JS 호출 URL과 최종 대조 예정.)

### [PASS] Controller ↔ Service ↔ Mapper 파라미터 정합
- `sessions()`: **countSessions(빈 param) 먼저 호출**(필터/페이징 키 없음 → 전체 건수) → 이후 startRow/pageSize를 param에 넣고 selectSessions 호출. count가 페이징 키에 영향받지 않는 순서라 정확. ✔
- createSession→`ChatService.createSession(title)`→`insertChatMaster`, messages→`selectMessages`→`selectChatDetailList(sessionId)`, docs→`saveUserMessage`/`saveAssistantMessage`→`insertChatDetail`. 파라미터 타입/이름 전부 정합. ✔
- `ChatService.createSession`이 UUID/owner/create/update_user_id(=DEFAULT_USER_ID "sunjeehun")/title 기본값("새로운채팅") 세팅 → 라운드1 [PASS] "세션 생성 규칙" 및 NOT NULL 제약(chat_owner_user_id/chat_title_name) 충족 재확인. ✔

### [RESOLVED] MINOR-1 (라운드1 이월) — 페이징 startRow+pageSize 동시 세팅
- `AIRestController.sessions()` 71-73: `param.put("startRow",(page-1)*rows); param.put("pageSize",rows);` — **항상 함께 세팅**. page1이면 startRow=0. mapper LIMIT/OFFSET 가드(`pageSize != null`)에서 OFFSET null 런타임 오류 발생 경로 없음. 라운드1 MINOR-1 해소. 
- (잔여 권고, 승격 아님) mapper XML 가드는 여전히 `pageSize`만 검사 — 향후 다른 호출부가 pageSize만 넘기면 재발 가능. 방어적으로 `pageSize != null and startRow != null`로 강화 권장(선택). 현재 유일 호출부가 안전하므로 MINOR 유지.

### [PARTIAL/이월→라운드3] MINOR-2 (라운드1 이월) — createDate 포맷
- `SessionRow`/`MessageRow` javadoc + backend_summary §4.1에 JDBC getString 실제 포맷 `"2026-07-08 10:11:12.0"`(소수부 `.0`) 명시하고 **그대로 노출**. 백엔드는 포맷을 가공하지 않음(설계 결정). 표시용 절삭은 프론트(T3) 책임으로 이관 명문화. → **라운드3 REST↔JS 검증에서 프론트가 `.0` 포함 포맷을 파싱/표시하는지 확인** 필요. 백엔드 계층 자체는 정합.

### [MINOR-3] 리액티브 체인 — 스트림 중도 실패 시 assistant 저장 누락
- `AIRestController.getDocs` 227: `saveUser.thenMany(textStream).concatWith(saveAssistantTail)`. `concatWith`는 **선행 스트림이 정상 complete일 때만** 후행(assistant 저장)을 구독. LLM 스트림이 중도 에러로 종료되면 assistant 저장 tail은 실행되지 않음 → user 메시지만 남고 답변 미저장.
- 설계 §8.2 "부분 실패 시 사용자 경험 우선"에 부합(치명 아님)하나, 세션 복원(C) 시 user 질문만 있고 assistant 응답이 비는 행이 남을 수 있음. 필요 시 `.onErrorResume`으로 부분 답변(누적 `answer`)을 저장하도록 보강 검토. 이중 저장/트랜잭션-스트림 결합 없음은 정상.

### [MINOR-4] 리액티브 체인 — `answer` StringBuilder 가시성
- `answer`(StringBuilder, 비스레드세이프)는 `textStream.doOnNext`에서 append, `saveAssistantTail`(boundedElastic 스레드)에서 read. reactor의 `concatWith` 순차 구독 경계가 happens-before를 보장하므로 실무상 안전. model/토큰은 `AtomicReference` 사용으로 가시성 확보. 기능 영향 없음(문서화 목적).

### [PASS] 리액티브 체인 안전성 — 트랜잭션 경계 / 중복 저장
- 스트림 전체를 트랜잭션으로 감싸지 않음. `saveUserMessage`/`saveAssistantMessage`는 각각 독립 `@Transactional`(별도 빈 프록시 호출, self-invocation 아님) → AOP 정상 적용. ✔
- user 저장은 `Mono.fromRunnable`+`boundedElastic`, 내부 try/catch로 예외 흡수(저장 실패해도 스트림 진행 — §8.2). assistant 저장도 동일 패턴. 블로킹 JDBC 오프로딩 정상. ✔
- 순서 보장: saveUser(complete) → textStream → saveAssistantTail. 단일 구독 체인이라 assistant 저장 **정확히 1회**(중복 없음). ✔
- `hasSession=false` 분기는 저장 없이 textStream만 반환(방어적). query 공백은 조기 안내 메시지. ✔

### [PASS] Spring AI 설정 ↔ 코드 (경계면 5)
- `application.yaml`: `spring.ai.openai.chat.options.model=gpt-4o-mini`, `stream-usage: true`(37행) 확인 — 설계 §10 요구 반영됨(usage 확보 활성). 임베딩 `text-embedding-3-small`, pgvector HNSW/COSINE/initialize-schema 기존 유지.
- `AIService.streamChat`은 `.chatResponse()`로 소비, `extractText` null-safe, `captureMetadata`가 `metadata.getModel()`·`Usage` 존재 시에만 세팅 → usage null이면 토큰 null 저장 fallback(설계 §5.2). model은 하드코딩이 아니라 metadata에서 동적 확보(계약상 gpt-4o-mini와 일치). ✔
- `getDocs(Flux<String>)`가 `streamChat` 위에서 텍스트 추출로 재구성 — 하위호환 유지. ✔

### 결론
T2 백엔드 계층 정합. **BLOCKER/MAJOR 없음.** 라운드1 MINOR-1 해소, MINOR-2는 프론트 표시 책임으로 라운드3 이월. 신규 MINOR-3/4(리액티브 엣지)는 설계 정책 범위 내 — 치명 아님. 컴파일 성공.
- 라운드3(T3 프론트) 확인 이월 항목: (1) JSP/JS 호출 URL ↔ /user/rag/sessions·/docs 경로 대조, (2) SessionListResponse.total=페이지 수 vs records=건수 프론트 오인 여부, (3) createDate `.0` 포맷 파싱/절삭, (4) role `assistant→bot` 매핑, (5) rows null-safe 파싱.

---

## 2026-07-08 라운드 3 (최종) — T3 (프론트엔드) 검증

검증자: integration-qa | 대상: `rag.jsp`(인라인 JS), `common.css` `.rag-session-*` | 기준: `00_architect_design.md §9`, `01_backend_summary.md §3`, `03_frontend_summary.md`

심각도 요약: **BLOCKER 0 / MAJOR 0 / MINOR 1 신규 · 라운드2 이월 MINOR-2 종결**

### [PASS] 경계면 4 — 호출 URL/메서드 ↔ 컨트롤러 경로 (항목1)
- A `GET /user/rag/sessions?page=1&rows=100`(rag.jsp:347) ↔ `@GetMapping("/sessions")` `@RequestParam page/rows` 기본 1/100. ✔
- B `POST /user/rag/sessions` body `{}`(rag.jsp:379-383) ↔ `@PostMapping("/sessions")` `@RequestBody(required=false) Map`. ✔
- C `GET /user/rag/sessions/{id}/messages`(rag.jsp:406, `encodeURIComponent(sessionId)`) ↔ `@GetMapping("/sessions/{sessionId}/messages")`. ✔
- D `POST /user/rag/docs`(rag.jsp:444) ↔ `@PostMapping("/docs")` TEXT_EVENT_STREAM. ✔
- 전체 URL 프리픽스 `/user/rag` + 상대경로 조합 정확, HTTP 메서드 4개 모두 일치.

### [PASS] 경계면 1 — REST 응답 shape ↔ JS 파서 전 필드 대조 (항목6)
- A `SessionRow{sessionId,title,createDate}` ↔ JS `s.sessionId`(357/368), `session.title`(319), `session.createDate`(323). ✔
- B `SessionCreateResponse{success,sessionId,title,message}` ↔ JS `data.success`(387), `data.sessionId`(387/395/400), `data.title`(396), `data.message`(388). ✔
- C `MessageRow{role,content,model,promptTokens,completionTokens,createDate}` ↔ JS `m.role`(421), `m.content`(422). model/토큰은 설계 §9.5대로 미사용(저장 전용) — 누락 아님. ✔
- D SSE `data:` 토큰 스트림 ↔ JS ReadableStream 파서(459-478): `\n\n` 이벤트 경계 분리, `data:` 접두 `slice(5)`, 멀티라인 `join('\n')`. 백엔드 `Flux<String>` + `TEXT_EVENT_STREAM_VALUE`와 정합(라운드2 D 포맷 불변 확인과 일치). ✔

### [RESOLVED] MINOR-2 (라운드1·2 이월) — createDate ".0" 절삭 최종 종결 (항목3)
- `formatDate(s)`(rag.jsp:303-308): `indexOf('.')` 기준 소수부 절삭 → `"2026-07-08 10:11:12.0"`→`"2026-07-08 10:11:12"`. `renderSessionItem` 날짜 표시에 적용(323). 세션 목록만 날짜 노출(메시지 복원 C는 createDate 미표시)이라 적용 범위 완결. **MINOR-2 종결.**

### [PASS] role "assistant"→bot 매핑 (항목4)
- `loadMessages`(rag.jsp:421): `var cls = (m.role === 'assistant') ? 'bot' : 'user'` → `appendMessage(cls,...)` → `chat-msg--bot`/`chat-msg--user`. CSS 클래스 `.chat-msg--bot`(common.css:839)/`.chat-msg--user`(835) 존재. 설계 §9.5 매핑 정확. ✔

### [PASS] rows null-safe / 빈·실패 응답 방어 (항목5)
- loadSessions(353)·loadMessages(412): `data && Array.isArray(data.rows) ? data.rows : []` 이중 가드. 빈 목록 → 안내문구(361)·`currentSessionId=null`. 실패 → catch에서 오류문구+`is-error`(372-373). createSession(387): `success && sessionId` 검증 실패 시 `data.message`로 throw. ✔

### [PASS] 이벤트 흐름 — 새 채팅(초기화) vs 자동 생성(초기화 안 함) (항목7)
- 새 채팅 `newBtn`(rag.jsp:550-562): `createSession()` → **`resetChatWindow()`**(채팅창 인사 1건 리셋) → focus. 설계 §9.4-b/요구3 일치. ✔
- 자동 생성 `handleSend`(487-497): `if(!currentSessionId) await createSession()` — **resetChatWindow 호출 없음**(주석 명시), 이어서 user 말풍선 추가. 설계 §9.4-d/요구4(초기화 안 함) 일치. ✔
- 두 경로의 초기화 차이가 요구사항과 정확히 대응. createSession이 `sessionMsgEl` 빈 상태문구 제거(391)까지 처리.

### [PASS] 회귀 — 카테고리/SSE/사이드바 토글 (항목8)
- 사이드바 토글 IIFE(140-153) 무변경. 카테고리 IIFE(156-209) 무변경, `getSelectedCategoryId`가 `#ragCatList .rag-cat-btn.is-selected` 참조 — DOM 기본 "전체" 버튼 `is-selected`(rag.jsp:110-111) 존재. streamAnswer SSE 파서 무변경, payload에 `sessionId`만 추가(442) + 기존 `categoryId` 로직(439-440) 보존. 회귀 없음. ✔

### [PASS] DOM id/class ↔ JS 셀렉터 정합 (항목9)
- id: chatMessages/chatForm/chatInput/chatSendBtn/ragSessionList/ragSessionMsg/ragSessionNewBtn/sidebar/toggleBtn/ragCatList/ragCatMsg — JS `getElementById` 대상 전부 마크업에 존재. ✔
- class/셀렉터: `.rag-session-item(.is-selected)`, `.rag-session-item__title/__date`, `.chat-msg--bot/--user`, `.chat-bubble`, `.rag-cat-btn.is-selected` — CSS 규칙(common.css:1077-1168, 835-859) 모두 정의. ✔
- 레이아웃: `.rag-session-panel flex:0 0 15%`(1078) < `.rag-cat-panel 20%`(1015) — 요구2(세션 패널이 카테고리보다 좁음) 충족. ✔

### [PASS] 항목2 — total/records 오인 여부
- `loadSessions`는 `data.rows`만 사용, `total`(페이지 수)/`records`(건수)를 **참조하지 않음** → 페이지수-건수 혼동 여지 없음. rows=100 단일 페이지 로딩으로 세션 목록 용도에 충분. ✔

### [MINOR-5] 신규(경미/UX) — 새 세션 항목 날짜 공란(새로고침 전)
- `SessionCreateResponse`에는 createDate 필드가 없어(설계대로) createSession이 `createDate:''`로 항목 렌더(rag.jsp:397) → 방금 만든 세션은 날짜가 빈칸. 새로고침/재로딩(GET A) 시 실제 생성일 표시됨. 기능 영향 없는 표시상의 미세 항목. 필요 시 B 응답에 createDate 추가 또는 클라이언트에서 현재시각 임시 표기 고려(선택).

### 결론 (라운드 3)
T3 프론트엔드 계층 정합. **BLOCKER/MAJOR 없음.** 라운드2 이월 5개 검증항목 전부 통과, MINOR-2(createDate 포맷) 종결. 신규 MINOR-5는 경미한 UX 항목.

---

## 종합 결론 — 전체 라운드 (T1 데이터 · T2 백엔드 · T3 프론트)

**최종 판정: BLOCKER 0 / MAJOR 0 — 통합 정합성 이상 없음. 릴리스 차단 요소 없음.**

| 라운드 | 대상 | 결과 | 잔여 |
|--------|------|------|------|
| 1 | T1 데이터(DTO/Mapper/XML/DDL) | PASS | MINOR-1·2 이월 |
| 2 | T2 백엔드(Service/Controller/DTO/AI) | PASS · compile OK | MINOR-1 해소, MINOR-2 이월, MINOR-3·4 신규 |
| 3 | T3 프론트(rag.jsp/CSS) | PASS | MINOR-2 종결, MINOR-5 신규 |

**5대 경계면 최종 상태**
- 경계면1 REST DTO↔JS 파서: PASS (Session/Message/Create 전 필드 일치, SSE 파서 호환)
- 경계면2 ResultMap↔DTO: PASS (chat_master 7 / chat_detail 11 property 일치)
- 경계면3 Mapper 파라미터↔XML #{}: PASS (@Param("sessionId") 포함 전부 정합)
- 경계면4 라우팅 URL↔호출 URL: PASS (/sessions·/messages·/docs GET/POST 일치)
- 경계면5 Spring AI 설정↔코드: PASS (gpt-4o-mini, stream-usage:true, usage null fallback)

**미해결/관찰 MINOR (치명 아님, 후속 개선 후보)**
- MINOR-3: LLM 스트림 중도 에러 시 `concatWith` 특성상 assistant 저장 누락(user만 저장). 설계 §8.2 부분실패 정책에 부합. 필요 시 `onErrorResume`로 부분 답변 저장 보강.
- MINOR-4: 컨트롤러 `answer` StringBuilder 가시성 — reactor 순차 경계로 실무상 안전(문서화).
- MINOR-5: 새 세션 항목 날짜 새로고침 전 공란(SessionCreateResponse에 createDate 없음).
- (선택) ChatMasterMapper.xml 페이징 가드를 `pageSize != null and startRow != null`로 강화(현 유일 호출부는 안전).

**런타임 전제(코드 정합과 별개, 사용자 확인 완료분)**
- chat_master/chat_detail 테이블 DB 적용 완료(코디네이터 확인). `stream-usage: true` yaml 반영 확인.

**권고**: BLOCKER/MAJOR 없음 → 통합 빌드/배포 진행 가능. 위 MINOR 4건은 릴리스 후 개선 백로그로 이관 권장.
