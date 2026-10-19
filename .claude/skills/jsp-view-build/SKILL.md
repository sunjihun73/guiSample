---
name: jsp-view-build
description: guiSample JSP 화면을 작성하는 방법. WEB-INF/views 의 JSP 골격(CSRF 메타, HtmlUtils 이스케이프), 정적 include 사이드바(activeMenu)·appDialog 조각, jQuery + free-jqGrid 목록(jqGrid shape 파싱), 레이어 팝업, fetch 기반 SSE 스트림, 단일 common.css, JS let 규칙, e2e 셀렉터 보존을 다룬다. jsp-frontend-engineer 가 사용한다. "화면 추가", "JSP 수정", "그리드 컬럼 추가", "팝업 추가", "채팅 화면 수정", "alert 를 appDialog 로" 요청 시 트리거.
---

## 사용 시점

jsp-frontend-engineer 가 화면을 만들거나 고칠 때. 먼저 **같은 종류의 기존 화면**을 읽는다: 목록+팝업 → `user/projects.jsp`, 단계 화면 → `user/workflow/step1.jsp`, 채팅·SSE → `user/rag.jsp`.

## 구조 (실제)

```
src/main/webapp/WEB-INF/views/
  common/sidebar.jsp, common/dialog.jsp     (정적 include 조각)
  user/*.jsp, user/workflow/*.jsp
src/main/resources/static/
  css/common.css                            (유일한 스타일 파일)
  js/csrf.js, js/dialog.js, js/{기능}.js
```

라우팅은 `MainController`(`/user/**`)에 `backend` 가 추가한다.

## JSP 골격

```jsp
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="org.springframework.web.util.HtmlUtils" %>
<%-- 사용자 유래 문자열은 출력 전에 HtmlUtils.htmlEscape — EL 은 자동 이스케이프가 없다 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/common.css">
    <meta name="_csrf" content="${_csrf.token}">
    <meta name="_csrf_header" content="${_csrf.headerName}">
    <%-- jqGrid 화면이면 jquery-ui / free-jqgrid CSS (projects.jsp 와 같은 버전) --%>
</head>
<body>
    ...
    <% String activeMenu = "menu-key"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>
    ...
<%@ include file="/WEB-INF/views/common/dialog.jsp" %>

<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<%-- jqGrid 화면이면 free-jqgrid 4.15.5 + grid.locale-kr --%>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>
<script src="${pageContext.request.contextPath}/js/dialog.js"></script>
<script src="${pageContext.request.contextPath}/js/{기능}.js"></script>
</body>
</html>
```

규칙과 이유:
- **사이드바는 정적 include** — `<jsp:include>` 는 `DispatcherType.INCLUDE` 를 일으키는데 `SecurityConfig` 는 FORWARD/ERROR 만 허용한다
- 새 조각 파일에는 `<%@ page pageEncoding="UTF-8" %>` 필수 — 없으면 ISO-8859-1 로 읽혀 한글이 깨진다
- 새 메뉴는 `sidebar.jsp` 한 곳에만 추가하고 `activeMenu` 키를 맞춘다
- `dialog.js` 는 기능 스크립트보다 **먼저** 로드
- `csrf.js` 가 jQuery ajax 에 CSRF 헤더를 자동 부착, fetch 는 `window.csrfHeader()` 로. 401/403 이면 페이지를 새로고침해 로그인으로 돌린다

## 목록 — jqGrid

- 서버 응답은 `{page, total, records, rows[]}`. `jsonReader` 는 기본 키 그대로 두고 `colModel.name` 을 `rows` 원소 DTO 필드명(camelCase)과 1:1 로
- 검색 조건 변경 시 `setGridParam({postData, page: 1}).trigger('reloadGrid')`
- 셀에 사용자 문자열을 HTML 로 넣는 `formatter` 는 이스케이프 후 반환

## 알림·확인 — appDialog

```javascript
appDialog.warn('이름을 입력하세요.');
appDialog.confirm('완료하시겠습니까?', {
    onConfirm: function () { doComplete(); },
    onCancel:  function () { /* 아무것도 안 함 */ }
});
```

- **스크립트를 멈추지 않는다.** `if (confirm(...))` 를 `if (appDialog.confirm(...))` 로 옮기면 항상 진행된다 — 이어질 동작은 반드시 `onConfirm` 안에
- 레이어 팝업(z-index 1000) 위에 뜨므로(1100) 등록 팝업 안 입력 검증에도 쓴다
- 새 코드에 `alert()`/`confirm()` 금지. e2e 가 기본 대화상자 발생을 실패로 감시한다

## 스트리밍(SSE) — fetch

```javascript
let res = await fetch(DOCS_URL, {
    method: 'POST',
    headers: Object.assign({'Content-Type': 'application/json', 'Accept': 'text/event-stream'}, window.csrfHeader()),
    body: JSON.stringify(payload)
});
if (res.status === 401 || res.status === 403) { window.location.reload(); return; }   // 세션 만료·CSRF 무효
let reader = res.body.getReader();
```

- 서버는 실패도 텍스트 1건 + 정상 종료(200)로 보낸다 — `res.ok` 만으로 성공 판단하지 말고 본문을 표시
- 스트림 텍스트는 `textContent` 로 누적

## CSS

- `common.css` 에만 추가. 기존 토큰·클래스(DESIGN.md 의 Apple 스타일 토큰)를 먼저 찾아 재사용
- 화면별 `.css` 파일, `style=""` 인라인 금지

## e2e 셀렉터 보존

Playwright 스펙(`e2e/specs/*.spec.ts`)은 JSP 의 실제 id/class 에 직접 의존한다(`chat.spec.ts` 상단에 목록). 기존 id/class 는 바꾸지 않는다. 불가피하면 스펙을 함께 고치고 `03_frontend_summary.md` 에 기록. 새 인터랙티브 요소에는 안정적인 `id` 를 붙인다.

## XSS 체크리스트

| 상황 | 방법 |
|------|------|
| JSP 에서 세션·DB 문자열 출력 | `HtmlUtils.htmlEscape(...)` 후 출력 |
| ajax 결과를 DOM 에 | `textContent` / jQuery `.text()` |
| jqGrid formatter | 이스케이프 후 HTML 조립 |
| 설명처럼 HTML 을 그대로 보여야 하는 값 | 저장·표시 경로를 설계 문서에서 확인(임의로 innerHTML 금지) |
| URL 에 사용자 입력 | `encodeURIComponent` |

## 작업 순서

1. 설계 문서 §5·§6, 같은 종류의 기존 JSP 정독
2. `backend` 응답 shape 확인(없으면 요청)
3. JSP → JS → common.css
4. 자체 검증(필드 교차 확인, 금지 패턴 grep, id/class 변경 여부)
5. `_workspace/03_frontend_summary.md` 작성, `qa` 에 검증 요청

## 흔한 함정

| 함정 | 해결 |
|------|------|
| `appDialog.confirm` 을 `if` 조건으로 | `onConfirm` 콜백 |
| 사이드바를 `<jsp:include>` | 정적 `<%@ include %>` |
| 조각 파일 한글 깨짐 | `pageEncoding="UTF-8"` |
| 정적 경로를 `/static/css/...` 로 | `${pageContext.request.contextPath}/css/...` |
| 화면 전용 CSS 파일 생성 | `common.css` 에 추가 |
| `var` 선언 | `let` |
| JSP 404 | `MainController` 라우팅 추가를 `backend` 에 요청 |

## 후속 작업

`_workspace/03_frontend_summary.md` 를 먼저 읽는다. 응답 shape 변경 통지가 오면 파서부터 갱신.
