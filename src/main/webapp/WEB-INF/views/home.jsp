<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%
    /* 로그아웃 직후 돌아오는 지점이기도 하므로 인증 여부에 따라 CTA 를 바꾼다. */
    boolean signedIn = request.getUserPrincipal() != null;
%>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>기술테스트 시스템</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/common.css">
</head>
<body>

<!-- ── Top Bar ── -->
<header class="topbar">
    <a href="/" class="topbar__brand">기술테스트 시스템</a>
</header>

<main class="landing">
    <div class="landing__card">
        <span class="landing__badge">Tech Test Platform</span>
        <h1 class="landing__title">기술테스트 시스템</h1>
        <% if (signedIn) { %>
            <p class="landing__desc">이미 로그인되어 있습니다. 대시보드에서 프로젝트와 RAG 챗봇을 이용하세요.</p>
            <a class="landing__btn" href="${pageContext.request.contextPath}/user/dashboard">
                대시보드로 이동
                <svg viewBox="0 0 16 16" width="16" height="16" fill="none" aria-hidden="true">
                    <path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" stroke-width="1.5"
                          stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
            </a>
        <% } else { %>
            <p class="landing__desc">계속하려면 Keycloak 계정으로 로그인하세요.</p>
            <a class="landing__btn"
               href="${pageContext.request.contextPath}/oauth2/authorization/keycloak">
                <svg viewBox="0 0 16 16" width="16" height="16" fill="none" aria-hidden="true">
                    <path d="M11 5.5V4a3 3 0 0 0-6 0v1.5" stroke="currentColor" stroke-width="1.5"
                          stroke-linecap="round"/>
                    <rect x="3" y="5.5" width="10" height="8" rx="2" stroke="currentColor" stroke-width="1.5"/>
                </svg>
                Keycloak 으로 로그인
            </a>
            <p class="landing__footnote">SSO 계정이 없다면 관리자에게 문의하세요.</p>
        <% } %>
    </div>
</main>

</body>
</html>
