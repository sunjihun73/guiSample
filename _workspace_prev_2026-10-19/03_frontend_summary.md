# 03. 프론트엔드 구현 요약 (T3 — jsp-frontend-engineer)

> 작성: jsp-frontend-engineer | 날짜: 2026-07-08
> 근거: `_workspace/00_architect_design.md` (§9 UI) + `_workspace/01_backend_summary.md` (§3 REST shape 확정본)
> 대상 화면: `GET /user/rag` → `WEB-INF/views/user/rag.jsp` (MainController 기존 라우트, 추가 라우팅 불필요)

## 1. 변경 파일

| 경로 | 구분 | 변경 내용 |
|------|------|-----------|
| `src/main/webapp/WEB-INF/views/user/rag.jsp` | 변경 | `.rag-body` 3단화(세션 패널 신규) + 스트리밍 IIFE를 세션 로직 통합 IIFE로 확장 |
| `src/main/resources/static/css/common.css` | 변경 | `.rag-session-*` 규칙 추가(기존 `.rag-cat-*` 미러링, `flex:0 0 15%`) |

- 별도 JS 파일 없음(기존 rag.jsp 인라인 `<script>` 관례 유지).
- 백엔드/컨트롤러 변경 요청 없음.

## 2. DOM 구조 (신규)

`.rag-body` = `[.rag-session-panel(15%)] [.rag-cat-panel(20%)] [.chat-window]` (요구1·2, 세션 패널이 카테고리보다 좁음)

```
<aside class="rag-session-panel">
  <div class="rag-session-panel__header">
    <p class="rag-session-panel__title">채팅세션</p>
    <button id="ragSessionNewBtn" class="rag-session-newbtn">새 채팅</button>
  </div>
  <div id="ragSessionList" class="rag-session-list"></div>   <!-- .rag-session-item(.is-selected) 동적 -->
  <p id="ragSessionMsg" class="rag-session-msg"></p>          <!-- 빈/오류 상태 -->
</aside>
```
세션 항목: `.rag-session-item` > `.rag-session-item__title`(title, textContent) + `.rag-session-item__date`(createDate 소수부 절삭). `dataset.sessionId` 보유.

## 3. 주요 함수 (rag.jsp 통합 IIFE 내부, `currentSessionId` 상태 공유)

| 함수 | 역할 | REST |
|------|------|------|
| `loadSessions()` | 페이지 로드 시 목록 로딩→렌더. 있으면 최상단(최신) 자동 활성+복원, 없으면 안내문구 | A |
| `createSession()` | 새 세션 생성→목록 최상단 prepend+활성화, sessionId 반환 | B |
| `loadMessages(id)` | 세션 대화 복원(채팅창 교체). `assistant→bot` 매핑 | C |
| `resetChatWindow()` | 채팅창을 기본 인사 1건으로 초기화 | - |
| `setActiveSession(id)` | `currentSessionId` 갱신 + `.is-selected` 이동 | - |
| `renderSessionItem(s,prepend)` | 세션 항목 DOM 생성(textContent, XSS 방지) | - |
| `formatDate(s)` | `"...12.0"`→`"...12"` 소수부 절삭 | - |
| `streamAnswer(q,onToken)` | 기존 SSE 파서 유지 + payload에 `sessionId` 추가 | D |
| `handleSend()` | 전송. `!currentSessionId`면 `createSession()` 선행(요구4) 후 전송 | B→D |

## 4. REST 호출 지점 & 응답 필드 사용표

| # | 호출 지점 | 메서드/경로 | 사용 응답 필드 |
|---|-----------|-------------|----------------|
| A | `loadSessions()` | GET `/user/rag/sessions?page=1&rows=100` | `rows[].sessionId`, `rows[].title`, `rows[].createDate` |
| B | `createSession()` | POST `/user/rag/sessions` body `{}` | `success`, `sessionId`, `title`, `message`(오류 표시) |
| C | `loadMessages(id)` | GET `/user/rag/sessions/{id}/messages` | `rows[].role`(user/assistant), `rows[].content` |
| D | `streamAnswer()` | POST `/user/rag/docs` body `{query, categoryId?, sessionId?}` | `text/event-stream` `data:` 토큰(기존 파서 불변) |

- 모든 필드명은 백엔드 DTO(`SessionRow`/`SessionCreateResponse`/`MessageRow` 등)와 grep 교차 확인 완료.
- C의 `model`/`promptTokens`/`completionTokens`는 이번 UI 미사용(저장만, 설계 §9.5).

## 5. 이벤트 흐름 자체 점검

- **초기 로드(요구7):** `loadSessions()` → 목록 있으면 최신 세션 활성+`loadMessages`로 복원, 없으면 인사만 유지·`currentSessionId=null`.
- **새 채팅(요구3):** `ragSessionNewBtn` 클릭 → `createSession()`(상단 추가·활성) → `resetChatWindow()` → 포커스.
- **자동 생성(요구4):** `handleSend()`에서 `if(!currentSessionId) await createSession()`(채팅창 초기화 안 함) 후 user 말풍선 추가·`streamAnswer`.
- **세션 전환(요구6):** `ragSessionList` 위임 클릭 → `setActiveSession`+`loadMessages`(동일 세션 재클릭 시 no-op).
- **전송(요구5):** `streamAnswer` payload에 활성 `sessionId` 항상 포함.

## 6. 안전/호환

- XSS: 모든 사용자 텍스트(title/content/message)는 `textContent`. `innerHTML`은 목록/채팅창 비우기(`= ''`)에만 사용.
- 기존 카테고리·SSE 스트리밍·사이드바 토글 IIFE 동작 불변(스트리밍은 payload `sessionId`만 추가).
- createDate `.0` 소수부는 표시 시 절삭.

## 7. QA(T4) 참고

- 화면-API 매핑: 위 §4 표. 회귀 확인 포인트: 카테고리 필터·기존 스트리밍(§5 "전송").
- 선결: `chat_master`/`chat_detail` 테이블 존재해야 A/B/C가 200. 미존재 시 세션 패널에 오류 문구 표시(채팅 스트리밍 자체는 sessionId 없이도 방어적으로 동작).

---

## 8. T7 — 세션 제목 자동 요약 반영 (후속)

> 근거: `_workspace/01_backend_summary.md` §7 (T6). 서버가 `/docs` 스트림 정상 종료 tail에서 비동기로
> 제목이 `"새로운채팅"`인 세션 1회만 요약 제목으로 갱신. SSE 포맷/응답 필드 불변, 갱신은 GET /sessions 재조회로만 확인.

### 8.1 변경 (rag.jsp 통합 IIFE)
| 요소 | 내용 |
|------|------|
| 상수 | `DEFAULT_TITLE = '새로운채팅'` 추가(자동 변경 트리거 판별) |
| 신규 함수 | `getSessionItem(sessionId)` — 목록에서 항목 DOM 순회 조회(속성 선택자 미사용) |
| 신규 함수 | `fetchSessionTitle(sessionId)` — GET /sessions(A) 재조회 후 해당 세션 title 반환 |
| 신규 함수 | `refreshSessionTitle(sessionId)` — 지연 재조회로 항목 title 텍스트만 갱신 |
| 배선 | `handleSend()`에서 `sendSessionId` 캡처(전송 시점) + 정상 답변 완료(`bubble` 존재) 분기에서 `refreshSessionTitle(sendSessionId)` 호출 |

### 8.2 동작 흐름 (요구1~5 대응)
1. SSE 스트림 **정상 종료(bubble 존재, 예외 아님)** 시에만 `refreshSessionTitle` 호출. 무응답(`!bubble`)·오류(catch)에서는 호출 안 함.
2. **가드**: 항목 제목이 아직 `DEFAULT_TITLE`일 때만 재조회 진입 → 이미 요약/수동 변경된 세션은 불필요한 재조회 없음(요구1·3).
3. **지연 재조회**: `delays=[1000, 3000]` — 1초 뒤 1회, 여전히 기본값이면 3초 뒤 1회. 각 시도 직전에도 기본값 재확인(중간 반영 시 조기 종료). 성공 시 즉시 중단(요구2).
4. **범위 격리**: 매칭 `sessionId` 항목의 `.rag-session-item__title` **textContent만** 교체 — 활성 선택 상태(`.is-selected`)·채팅창·`currentSessionId` 불변(요구2·3·4).
5. `sendSessionId`를 전송 시점에 캡처 → 스트리밍 중 사용자가 다른 세션으로 전환해도 올바른 항목을 갱신.
6. 재조회 실패는 조용히 무시(제목 기본값 유지, 채팅 흐름 무영향). 기존 스트리밍 파서/세션 전환/새 채팅 회귀 없음(요구5).

### 8.3 QA(T7) 참고
- 최초 대화 후 1~4초 내 세션 항목 제목이 요약 제목으로 바뀌는지 확인. 활성/채팅창 유지 확인.
- 2회차 이후 대화·수동 제목 세션: 재조회 미발생(제목 기본값 아님).
