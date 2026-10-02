---
name: jsp-view-build
description: JSP + CSS + Vanilla JS 프론트엔드 코드를 작성할 때 사용. WEB-INF/views/ 하위 JSP, common.css 확장, fetch+async/await 기반 REST 클라이언트, 채팅·검색 UI, JSTL/EL 사용, XSS 방지를 일관되게 적용. 백엔드가 확정한 REST 응답 shape을 정확히 파싱. jsp-frontend-engineer 에이전트 전용. "JSP 뷰 생성", "채팅 UI", "검색 화면", "프론트 RAG 통합" 요청 시 반드시 트리거.
---

## 사용 시점

jsp-frontend-engineer 에이전트가 RAG 기능의 화면을 만들 때.

## 디렉토리 구조

```
src/main/webapp/WEB-INF/views/
  └── user/*.jsp                     (사용자 화면. prefix=/WEB-INF/views/, suffix=.jsp)
src/main/resources/static/
  ├── css/common.css                 (전역 스타일 확장)
  ├── css/{page}.css                 (페이지 전용, 필요 시)
  └── js/{page}.js                   (페이지 전용 스크립트)
```

application.yaml: `spring.mvc.view.prefix=/WEB-INF/views/`, `suffix=.jsp`.

## JSP 패턴

```jsp
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <title>RAG 질의</title>
    <link rel="stylesheet" href="/css/common.css">
    <link rel="stylesheet" href="/css/rag.css">
</head>
<body>
    <main class="rag-page">
        <h1>RAG 질의</h1>
        <form id="ragForm" onsubmit="return false;">
            <textarea id="question" placeholder="질문을 입력하세요"></textarea>
            <button type="button" id="askBtn">질문하기</button>
        </form>
        <section id="answer" class="answer-box"></section>
        <section id="sources" class="sources-box"></section>
    </main>
    <script src="/js/rag.js"></script>
</body>
</html>
```

원칙:
- taglib URI는 Spring Boot 4 / Jakarta EE 기반이므로 `jakarta.tags.core`
- 전역은 common.css, 페이지 전용은 별도 파일
- 인라인 JS 지양. 외부 파일 분리
- 사용자 입력 표시 시 `<c:out>` 또는 JS의 `textContent` 사용

## 클라이언트 JS 패턴

```javascript
// /static/js/rag.js
const askBtn = document.getElementById('askBtn');
const questionEl = document.getElementById('question');
const answerEl = document.getElementById('answer');
const sourcesEl = document.getElementById('sources');

askBtn.addEventListener('click', async () => {
    const question = questionEl.value.trim();
    if (!question) return;

    answerEl.textContent = '답변 생성 중...';
    sourcesEl.innerHTML = '';

    try {
        const res = await fetch('/api/rag/query', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({question})
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        // 응답 shape: { answer: string, sources: [{title, snippet}] }
        answerEl.textContent = data.answer;
        if (Array.isArray(data.sources)) {
            data.sources.forEach(s => {
                const li = document.createElement('li');
                li.textContent = `${s.title} — ${s.snippet}`;
                sourcesEl.appendChild(li);
            });
        }
    } catch (e) {
        answerEl.textContent = `오류: ${e.message}`;
    }
});
```

원칙:
- async/await + try/catch. 에러는 사용자에게 표시
- 응답 필드는 백엔드 DTO와 정확히 일치 (spring-backend-engineer가 SendMessage로 공유한 shape)
- 사용자 입력/응답 표시는 `textContent`. innerHTML 사용 시 정제된 내부 데이터만

## CSS 확장 패턴

common.css에 이미 있는 변수/유틸리티 우선 사용. 없으면 페이지 전용 CSS에 신규 정의.

## 라우팅 추가

새 JSP 페이지를 만들면 컨트롤러 라우팅이 필요. jsp-frontend-engineer는 직접 컨트롤러를 작성하지 말고 spring-backend-engineer에 `SendMessage`:

```
[요청] /user/rag → user/rag.jsp 라우팅 필요
MainController 또는 새 컨트롤러에 @GetMapping("/user/rag") 추가 부탁
```

## 작업 순서

1. `_workspace/00_architect_design.md`의 UI 시나리오·API 스펙 정독
2. spring-backend-engineer로부터 응답 shape SendMessage 확인 (없으면 요청)
3. JSP 작성 → CSS 작성 → JS 작성
4. 사용한 응답 필드를 백엔드 DTO와 grep으로 교차 확인
5. `_workspace/03_frontend_summary.md`에 화면 라우트, 호출하는 엔드포인트, 응답 필드 사용 표 작성
6. integration-qa에 `SendMessage`로 검증 요청

## XSS 방지 체크리스트

| 시나리오 | 안전한 방법 |
|---------|----------|
| 서버에서 받은 사용자 데이터를 JSP에 표시 | `<c:out value="${...}"/>` |
| fetch 결과를 DOM에 표시 | `element.textContent = data.x` |
| HTML 구조가 포함된 마크다운/리치 텍스트 | DOMPurify 등 정제 후 innerHTML (의존성 추가는 사용자 협의 필요) |
| URL에 사용자 입력 | `encodeURIComponent` |

## 흔한 함정

| 함정 | 해결 |
|------|-----|
| `${question}` 같은 EL이 의도와 달리 평가됨 | JSP의 EL 충돌 시 `\${...}` 이스케이프 |
| JSP가 404 — 라우팅 미등록 | 컨트롤러 `@GetMapping` 추가 요청 |
| 응답 필드명 오타 | 백엔드 DTO와 1:1 매칭 검증 |
| CSS 파일이 로드 안됨 | `/static/` 경로는 빠지므로 `/css/...`로 |

## 후속 작업

`_workspace/03_frontend_summary.md`가 있으면 먼저 읽는다. 백엔드 응답 shape 변경 통지가 오면 영향받는 JS의 파서를 우선 갱신.
