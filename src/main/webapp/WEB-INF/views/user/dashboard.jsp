<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="org.springframework.web.util.HtmlUtils" %>
<%@ page import="kr.co.jihun.guisample.dto.SessionUser" %>
<%
    /* 상단바 표시 이름 — 로그인 직후 필터가 세션에 넣어 둔 SessionUser 의 display_name 을 쓴다.
       display_name 이 비어 있으면 user_name 으로 대체한다(상단바가 빈 칸이 되지 않도록).
       JSP EL 은 자동 이스케이프가 없으므로 출력 전에 직접 이스케이프한다. */
    SessionUser loginUser = (SessionUser) session.getAttribute(SessionUser.SESSION_KEY);

    String rawName = (loginUser != null) ? loginUser.getDisplayName() : null;
    if (rawName == null || rawName.isBlank())
    {
        rawName = (loginUser != null) ? loginUser.getUserName() : null;
    }

    String loginDisplayName = (rawName != null) ? HtmlUtils.htmlEscape(rawName) : "";
%>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>대시보드 — 기술테스트 시스템</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/common.css">
</head>
<body>

<!-- ── Top Bar ── -->
<header class="topbar">
    <a href="/" class="topbar__brand">기술테스트 시스템</a>
    <div class="topbar__user">
        <span class="topbar__username"><%= loginDisplayName %></span>
        <form action="${pageContext.request.contextPath}/logout" method="post">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
            <button type="submit" class="topbar__logout">로그아웃</button>
        </form>
    </div>
</header>

<div class="app-shell">

    <!-- ── Sidebar ── -->
    <% String activeMenu = "dashboard"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Overview</p>
            <h1 class="page-header__title">대시보드</h1>
            <p class="page-header__desc">프로젝트 진행 현황과 최근 활동을 한눈에 확인하세요.</p>
        </header>

        <div class="stat-grid">
            <div class="stat-card">
                <div class="stat-card__head">
                    <p class="stat-card__label">전체 프로젝트</p>
                    <svg class="stat-card__icon" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M1.5 4.5A1.5 1.5 0 0 1 3 3h3l1.5 1.5H13A1.5 1.5 0 0 1 14.5 6v6A1.5 1.5 0 0 1 13 13.5H3A1.5 1.5 0 0 1 1.5 12V4.5Z"
                              stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                    </svg>
                </div>
                <p class="stat-card__value">12</p>
                <p class="stat-card__delta up">
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="none" aria-hidden="true">
                        <path d="M8 12.5v-9M4.5 7 8 3.5 11.5 7" stroke="currentColor" stroke-width="1.5"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    +2 이번 달
                </p>
            </div>
            <div class="stat-card">
                <div class="stat-card__head">
                    <p class="stat-card__label">진행 중</p>
                    <svg class="stat-card__icon" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.3"/>
                        <path d="M8 4.5V8l2.5 1.5" stroke="currentColor" stroke-width="1.3"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </div>
                <p class="stat-card__value">5</p>
                <p class="stat-card__delta">변동 없음</p>
            </div>
            <div class="stat-card">
                <div class="stat-card__head">
                    <p class="stat-card__label">완료</p>
                    <svg class="stat-card__icon" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.3"/>
                        <path d="m5.5 8 1.8 1.8 3.2-3.6" stroke="currentColor" stroke-width="1.3"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </div>
                <p class="stat-card__value">7</p>
                <p class="stat-card__delta up">
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="none" aria-hidden="true">
                        <path d="M8 12.5v-9M4.5 7 8 3.5 11.5 7" stroke="currentColor" stroke-width="1.5"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    +1 이번 주
                </p>
            </div>
            <div class="stat-card">
                <div class="stat-card__head">
                    <p class="stat-card__label">팀원</p>
                    <svg class="stat-card__icon" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <circle cx="6" cy="5.5" r="2.5" stroke="currentColor" stroke-width="1.3"/>
                        <path d="M1.5 13c0-2.2 2-4 4.5-4s4.5 1.8 4.5 4M11 3.2a2.5 2.5 0 0 1 0 4.6M12.2 9.4c1.4.6 2.3 1.9 2.3 3.6"
                              stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                </div>
                <p class="stat-card__value">24</p>
                <p class="stat-card__delta up">
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="none" aria-hidden="true">
                        <path d="M8 12.5v-9M4.5 7 8 3.5 11.5 7" stroke="currentColor" stroke-width="1.5"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    +3 신규
                </p>
            </div>
        </div>

        <h2 class="section-title">최근 활동</h2>
        <p class="section-desc">최근 발생한 변경 사항입니다.</p>
        <div class="activity-list">
            <div class="activity-item">
                <span class="activity-item__dot"></span>
                <span class="activity-item__text">프로젝트 <strong>Alpha</strong> 가 완료되었습니다.</span>
                <span class="activity-item__time">방금 전</span>
            </div>
            <div class="activity-item">
                <span class="activity-item__dot"></span>
                <span class="activity-item__text">새 프로젝트 <strong>Beta</strong> 가 생성되었습니다.</span>
                <span class="activity-item__time">1시간 전</span>
            </div>
            <div class="activity-item">
                <span class="activity-item__dot"></span>
                <span class="activity-item__text">팀원 <strong>김철수</strong> 님이 합류했습니다.</span>
                <span class="activity-item__time">3시간 전</span>
            </div>
            <div class="activity-item">
                <span class="activity-item__dot"></span>
                <span class="activity-item__text">프로젝트 <strong>Gamma</strong> 마일스톤 달성.</span>
                <span class="activity-item__time">어제</span>
            </div>
        </div>

    </main>
</div>

<script>
    (function () {
        let sidebar   = document.getElementById('sidebar');
        let toggleBtn = document.getElementById('toggleBtn');
        let STORAGE_KEY = 'sidebar_collapsed';

        /* 저장된 상태 복원 */
        if (sessionStorage.getItem(STORAGE_KEY) === 'true') {
            sidebar.classList.add('collapsed');
        }

        toggleBtn.addEventListener('click', function () {
            let isCollapsed = sidebar.classList.toggle('collapsed');
            sessionStorage.setItem(STORAGE_KEY, isCollapsed);
        });
    })();
</script>

</body>
</html>
