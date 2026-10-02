# 03. 프론트 구현 요약 — Embedding 파일 업로드 UI

> 작성: jsp-frontend-engineer | 날짜: 2026-06-05
> 기준: `_workspace/00_architect_design.md` (7절 UI 시나리오), `_workspace/01_backend_summary.md` (REST 응답 shape)

## 수정/생성 파일

| 구분 | 경로 |
|------|------|
| 수정 | `src/main/webapp/WEB-INF/views/user/embedding.jsp` |
| 수정 | `src/main/resources/static/css/common.css` (Embedding 업로드 카드 스타일 추가) |

## 화면 라우트 (기존, 변경 없음)

- `GET /user/embedding` → `WEB-INF/views/user/embedding.jsp` (MainController 기존 매핑)
- 라우팅 컨트롤러 추가 요청 불필요.

## 호출 REST 엔드포인트

| Method | Path | Content-Type | 요청 필드 |
|--------|------|--------------|-----------|
| POST | `${pageContext.request.contextPath}/user/rag/embedding` | `multipart/form-data` (브라우저 자동 boundary) | `file`(File), `source`(text) |

- `FormData`에 `file`, `source` append → `fetch(URL, { method:'POST', body: formData })`.
- **`Content-Type` 헤더 직접 지정하지 않음** (브라우저가 multipart boundary 자동 설정).

## 응답 필드 사용 표 (백엔드 `EmbeddingResponse` record와 교차 확인 완료)

| 응답 키 | 타입 | JSP 사용처 | 표시 |
|---------|------|-----------|------|
| `success` | boolean | 분기 기준 | true → 성공, false → 실패 |
| `source` | string | 성공 메시지 | `"<source> · <chunkCount>개 청크 저장 완료"` (null이면 입력값 폴백) |
| `chunkCount` | number(int) | 성공 메시지 | 위 문자열의 청크 수 (null이면 0) |
| `message` | string | 실패 메시지 | 실패 시 그대로 표시 (없으면 기본 문구) |

- 4개 키 모두 `dto/EmbeddingResponse.java`(record `boolean success, String source, int chunkCount, String message`)에 존재함을 grep으로 확인.
- 성공/실패 모두 HTTP 200이므로 `res.ok` 통과 후 `success` 불리언으로만 분기. HTTP 에러(4xx/5xx)는 `throw new Error('HTTP ' + status)` → catch에서 오류 표시.

## 사용한 기존 common.css 클래스 (재사용)

- `.form-field`, `.form-field__label`, `.form-field__input` — source 텍스트 입력
- `.search-form__btn` — 업로드 버튼 (다른 화면 버튼과 동일 스타일)
- `.page-header`, `.page-header__title`, `.main-content` — 레이아웃 (기존)
- 사이드바/토픽바/nav 아이콘 — 변경 없음

## 추가한 common.css 클래스 (인라인 style 금지 준수, 파일 말미에 추가)

| 클래스 | 용도 |
|--------|------|
| `.embed-card` | 업로드 폼 카드 컨테이너 (canvas 배경 + hairline border + rounded-lg, 기존 카드 토큰 일관) |
| `.embed-file` | `<input type="file">` 외형 (`.form-field__input` 유사, focus 동일 포커스 링) |
| `.embed-card__actions` | 버튼 영역 flex |
| `.embed-result` | 결과 표시 영역 (`#embedResult`), `:empty`면 숨김 |
| `.embed-result.is-success` / `.is-error` / `.is-loading` | 상태별 색상 |

> 모두 기존 CSS 변수(`--canvas`, `--hairline`, `--rounded-*`, `--sp-*`, `--primary-focus` 등) 사용.

## 동작 / XSS

- 폼 submit `preventDefault` + 버튼 `click` 양쪽 바인딩.
- source 미입력/파일 미선택 시 결과 영역 안내 후 중단.
- 처리 중 버튼 `disabled` + "업로드 중..." 표시, `finally`에서 라벨/활성 복원.
- 모든 동적 텍스트는 `textContent`로만 삽입(innerHTML 미사용). source는 사용자 입력이므로 특히 textContent 처리.
- 네트워크/파싱 예외는 `try/catch`로 결과 영역에 오류 메시지 표시.

## 정리한 잔재

- 본문에 없는 요소(`chatMessages`/`chatForm`/`chatInput`/`chatSendBtn`)를 참조하던 RAG 챗봇 스트리밍 IIFE(`DOCS_URL`, `streamAnswer`, `appendMessage` 등) 전부 제거 → 업로드 IIFE로 교체. grep으로 잔재 0건 확인.
- `<main class="main-content rag-chat">` → `<main class="main-content">` (챗봇 레이아웃 클래스 제거).
- **사이드바 토글 IIFE는 유지** (`toggleBtn` / `sidebar_collapsed` sessionStorage).

## integration-qa 안내 (화면-API 매핑)

- 화면: `/user/embedding` → 업로드 폼(`#embedForm`, source `#embedSource`, 파일 `#embedFile`, 버튼 `#embedBtn`, 결과 `#embedResult`).
- API: `POST /user/rag/embedding` multipart(`file`,`source`) → `{success, source, chunkCount, message}`.
- 성공 시 결과 영역에 `"<source> · <chunkCount>개 청크 저장 완료"`(녹색), 실패 시 `message`(빨강) 표시.

## 2026-06-12 카테고리 패널 추가 (rag.jsp)

- 레이아웃: page-header 아래를 `.rag-body`(flex)로 감싸 좌측 `.rag-cat-panel`(flex 0 0 20%) + 우측 기존 `.chat-window`(flex 1) 2단 구성.
- 카테고리 목록: `GET /user/category/categories?page=1&rows=1000` → `rows[].categoryId/categoryName`만 사용.
  - 그리드 미사용 — `.rag-cat-btn` 버튼 세로 목록(`.rag-cat-list`, flex-column).
  - `categoryName`은 `textContent` 삽입(XSS 방지), `categoryId`는 `data-category-id`에 보관.
- 선택: 단일 선택, `is-selected` 클래스 + `aria-pressed` 토글. 최상단 "전체" 버튼(data-category-id="")이 기본 선택.
- 전송: `streamAnswer`가 전송 시점에 `#ragCatList .rag-cat-btn.is-selected`의 `data-category-id`를 읽어
  값이 있으면 body에 `categoryId` 포함, "전체"면 필드 생략 → `POST /user/rag/docs {query[, categoryId]}`.
- 로딩 실패 시 `#ragCatMsg`(`.rag-cat-msg.is-error`)에 오류 표시, 채팅("전체" 검색)은 계속 동작.
- common.css 추가 클래스: `.rag-body`, `.rag-cat-panel(__title)`, `.rag-cat-list`, `.rag-cat-btn(.is-selected)`, `.rag-cat-msg(.is-error)`.

## 후속 추가 (2026-06-15) — 지식파일 상세(청크 목록) 팝업
- `embedding.jsp` 지식파일 그리드에 "상세" 컬럼(버튼) 추가 → `.grid-detail-btn` 위임 클릭(stopPropagation)
- 청크 팝업 `#chunkPopup`(닫기 버튼만), GET chunkings 호출 후 textContent 로 렌더(XSS 방지)
- 응답 파싱: chunk.chunkIndex / chunk.content
- `common.css`: `.layer-popup__panel--wide`, `.grid-detail-btn`, `.chunk-list*` 추가
