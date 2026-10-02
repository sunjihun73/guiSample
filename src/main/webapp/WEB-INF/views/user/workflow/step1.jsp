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
    <title>스텝1 — 기술테스트 시스템</title>
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
    <% String activeMenu = "workflow-step1"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Workflow</p>
            <h1 class="page-header__title">스텝1</h1>
            <p class="page-header__desc">태스크를 생성하고, 스텝1 단계에 있는 태스크를 완료해 스텝2 로 넘깁니다.</p>
        </header>

        <!-- 진행 단계 표시 — 현재 화면이 어느 단계인지 -->
        <div class="wf-steps" aria-label="워크플로우 단계">
            <span class="wf-steps__item is-current">스텝1</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item">스텝2</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item">스텝3</span>
            <span class="wf-steps__arrow" aria-hidden="true">→</span>
            <span class="wf-steps__item">종료</span>
        </div>

        <!-- 태스크 생성 — 이름과 함께 보내면 task_master 에 업무 정보가 남고 스텝1 태스크가 생긴다 -->
        <div class="search-form">
            <label for="newTaskName" class="search-form__label">태스크이름</label>
            <input type="text" id="newTaskName" name="taskName"
                   class="search-form__input" placeholder="태스크 이름을 입력하세요" maxlength="200">
            <div class="search-form__actions">
                <button type="button" id="btnRefresh" class="search-form__btn search-form__btn--ghost">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        <path d="M13.5 2.5V5H11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    새로고침
                </button>
                <button type="button" id="btnCreateTask" class="search-form__btn">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                    </svg>
                    태스크생성
                </button>
            </div>
        </div>


        <!-- 이 단계에 머물러 있는 내 태스크 -->
        <div class="grid-wrap">
            <table id="workflowGrid"></table>
            <div id="workflowGridPager"></div>
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
        workflow.initTaskGrid({
            step:    'step1',
            gridId:  '#workflowGrid',
            pagerId: '#workflowGridPager',
            caption: '스텝1 진행 중 태스크'
        });

        /* 태스크 이름을 함께 보내고, 생성 직후 목록에 바로 보이도록 같은 그리드를 다시 읽는다. */
        workflow.bindCreateButton('#btnCreateTask', {
            nameInputId: '#newTaskName',
            gridId:      '#workflowGrid'
        });

        $('#btnRefresh').on('click', function () {
            workflow.reloadGrid('#workflowGrid');
        });
    });
</script>

</body>
</html>
