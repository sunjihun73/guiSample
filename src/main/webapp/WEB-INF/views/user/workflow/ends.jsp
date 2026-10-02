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
    <title>종료 — 기술테스트 시스템</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/common.css">

    <%-- CSRF 토큰 — /js/csrf.js 가 읽어 모든 비-GET ajax 요청 헤더에 부착한다. --%>
    <meta name="_csrf" content="${_csrf.token}">
    <meta name="_csrf_header" content="${_csrf.headerName}">

    <!-- jQuery UI (jqGrid 테마) -->
    <link rel="stylesheet" href="https://code.jquery.com/ui/1.13.2/themes/base/jquery-ui.min.css">
    <!-- free-jqGrid CSS -->
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/css/ui.jqgrid.min.css">
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
    <% String activeMenu = "workflow-ends"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Workflow</p>
            <h1 class="page-header__title">종료</h1>
            <p class="page-header__desc">스텝3 까지 완료되어 종료된 태스크를 조회합니다.</p>
        </header>

        <!-- 진행 단계 표시 — 현재 화면이 어느 단계인지 -->
        <div class="wf-steps" aria-label="워크플로우 단계">
            <span class="wf-steps__item">스텝1</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item">스텝2</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item">스텝3</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item is-current">종료</span>
        </div>

        <div class="search-form">
            <span class="search-form__label">스텝3 에서 완료된 태스크가 이 목록에 나타납니다. 더 진행할 단계가 없습니다.</span>
            <div class="search-form__actions">
                <button type="button" id="btnRefresh" class="search-form__btn search-form__btn--ghost">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        <path d="M13.5 2.5V5H11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    새로고침
                </button>
            </div>
        </div>

        <!-- 종료된 내 태스크 (읽기 전용 — 완료 버튼이 없다) -->
        <div class="grid-wrap">
            <table id="finishedGrid"></table>
            <div id="finishedGridPager"></div>
        </div>

    </main>
</div>

<%-- 알림/확인 대화상자 — 기본 alert()/confirm() 대신 쓴다(동작은 /js/dialog.js) --%>
<%@ include file="/WEB-INF/views/common/dialog.jsp" %>

<!-- jQuery / jqGrid -->
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/jquery.jqgrid.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/i18n/grid.locale-kr.min.js"></script>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>
<script src="${pageContext.request.contextPath}/js/dialog.js"></script>
<script src="${pageContext.request.contextPath}/js/workflow.js"></script>

<script>
    (function () {
        let sidebar   = document.getElementById('sidebar');
        let toggleBtn = document.getElementById('toggleBtn');
        let STORAGE_KEY = 'sidebar_collapsed';

        if (sessionStorage.getItem(STORAGE_KEY) === 'true') {
            sidebar.classList.add('collapsed');
        }

        toggleBtn.addEventListener('click', function () {
            let isCollapsed = sidebar.classList.toggle('collapsed');
            sessionStorage.setItem(STORAGE_KEY, isCollapsed);
        });
    })();

    $(function () {
        workflow.initFinishedGrid({
            gridId:  '#finishedGrid',
            pagerId: '#finishedGridPager',
            caption: '종료된 태스크',
            height:  '420'
        });

        $('#btnRefresh').on('click', function () {
            workflow.reloadGrid('#finishedGrid');
        });
    });
</script>

</body>
</html>
