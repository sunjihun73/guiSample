<%@ page pageEncoding="UTF-8" %>
<%-- 정적 include 는 포함되는 파일의 인코딩을 따로 판단한다(미지정 시 ISO-8859-1).
     포함하는 페이지의 contentType charset 은 여기까지 전달되지 않으므로 직접 지정한다. --%>
<%--
    좌측 사이드바 공통 조각.

    사용법 — 포함하는 화면이 include 앞에서 activeMenu 를 선언한다.

        <% String activeMenu = "dashboard"; %>
        <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    activeMenu 값: dashboard | projects | rag | embedding | workflow-step1 | workflow-step2 | workflow-step3 | workflow-ends | repositories

    <jsp:include>(런타임 include) 가 아니라 <%@ include %>(정적 include)를 쓴다.
    런타임 include 는 DispatcherType.INCLUDE 디스패치를 일으키는데 SecurityConfig 는
    FORWARD/ERROR 만 permitAll 로 열어 두었다. 정적 include 는 컴파일 시점에 본문이 합쳐져
    디스패치가 아예 없으므로 보안 설정을 건드리지 않는다.
--%>
<%
    String sidebarActive = (activeMenu != null) ? activeMenu : "";
    boolean workflowOpen = sidebarActive.startsWith("workflow");
%>
<aside class="sidebar" id="sidebar">

    <!-- 토글 버튼 -->
    <div class="sidebar__toggle">
        <button class="toggle-btn" id="toggleBtn" aria-label="메뉴 접기/펼치기" title="메뉴 접기/펼치기">
            <!-- 왼쪽 화살표 (펼쳐진 상태) → 접히면 CSS로 180도 회전 -->
            <svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M10 3L5 8L10 13" stroke="currentColor" stroke-width="1.5"
                      stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
        </button>
    </div>

    <p class="sidebar__section-label">메뉴</p>

    <ul class="nav-menu">
        <li class="nav-menu__item<%= "dashboard".equals(sidebarActive) ? " active" : "" %>">
            <a href="/user/dashboard">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                    <rect x="1" y="1" width="6" height="6" rx="1.5"/>
                    <rect x="9" y="1" width="6" height="6" rx="1.5"/>
                    <rect x="1" y="9" width="6" height="6" rx="1.5"/>
                    <rect x="9" y="9" width="6" height="6" rx="1.5"/>
                </svg>
                <span class="nav-label">대시보드</span>
            </a>
            <span class="tooltip">대시보드</span>
        </li>
        <li class="nav-menu__item<%= "projects".equals(sidebarActive) ? " active" : "" %>">
            <a href="/user/projects">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                    <path fill-rule="evenodd" clip-rule="evenodd"
                          d="M1 4a2 2 0 0 1 2-2h3.172a2 2 0 0 1 1.414.586l.828.828A1 1 0 0 0 9.121 4H13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V4Zm2-.5a.5.5 0 0 0-.5.5v8a.5.5 0 0 0 .5.5h10a.5.5 0 0 0 .5-.5V6a.5.5 0 0 0-.5-.5H9.121a2 2 0 0 1-1.414-.586l-.828-.828A.5.5 0 0 0 6.172 3.5H3Z"/>
                </svg>
                <span class="nav-label">프로젝트</span>
            </a>
            <span class="tooltip">프로젝트</span>
        </li>
        <li class="nav-menu__item<%= "rag".equals(sidebarActive) ? " active" : "" %>">
            <a href="/user/rag">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                    <path fill-rule="evenodd" clip-rule="evenodd"
                          d="M3 2.5A1.5 1.5 0 0 0 1.5 4v6A1.5 1.5 0 0 0 3 11.5h1.5v2.19a.5.5 0 0 0 .82.385L8.68 11.5H13A1.5 1.5 0 0 0 14.5 10V4A1.5 1.5 0 0 0 13 2.5H3ZM3 4h10v6H8.5a.5.5 0 0 0-.32.115L6 11.94V11a1 1 0 0 0-1-1H3V4Z"/>
                    <circle cx="5.5" cy="7" r="0.9"/>
                    <circle cx="8" cy="7" r="0.9"/>
                    <circle cx="10.5" cy="7" r="0.9"/>
                </svg>
                <span class="nav-label">RAG 챗봇</span>
            </a>
            <span class="tooltip">RAG 챗봇</span>
        </li>
        <li class="nav-menu__item<%= "embedding".equals(sidebarActive) ? " active" : "" %>">
            <a href="/user/embedding">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                    <path d="M3.6 3.9 12.4 3.9M3.6 3.9 7.7 12.1M12.4 3.9 7.7 12.1"
                          stroke="currentColor" stroke-width="1.1" fill="none"/>
                    <circle cx="3.6" cy="3.9" r="1.9"/>
                    <circle cx="12.4" cy="3.9" r="1.9"/>
                    <circle cx="7.7" cy="12.1" r="1.9"/>
                </svg>
                <span class="nav-label">Embedding</span>
            </a>
            <span class="tooltip">Embedding</span>
        </li>

        <!-- 워크플로우 — 하위에 스텝1/2/3 (Flowable userTask 와 1:1) + 종료(끝난 프로세스 조회) -->
        <li class="nav-menu__item nav-menu__item--group<%= workflowOpen ? " is-open" : "" %>" id="navWorkflowGroup">
            <a href="#" class="nav-menu__group-toggle" id="navWorkflowToggle"
               role="button" aria-expanded="<%= workflowOpen %>" aria-controls="navWorkflowSubmenu">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <rect x="1.5" y="2" width="5" height="4" rx="1.2" stroke="currentColor" stroke-width="1.3"/>
                    <rect x="9.5" y="10" width="5" height="4" rx="1.2" stroke="currentColor" stroke-width="1.3"/>
                    <path d="M6.5 4H10a2 2 0 0 1 2 2v4" stroke="currentColor" stroke-width="1.3"
                          stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M10.4 8.6 12 10.2l1.6-1.6" stroke="currentColor" stroke-width="1.3"
                          stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span class="nav-label">워크플로우</span>
                <svg class="nav-chevron" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m6 4 4 4-4 4" stroke="currentColor" stroke-width="1.5"
                          stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
            </a>
            <span class="tooltip">워크플로우</span>

            <ul class="nav-submenu" id="navWorkflowSubmenu">
                <li class="nav-submenu__item<%= "workflow-step1".equals(sidebarActive) ? " active" : "" %>">
                    <a href="/user/workflow/step1"><span class="nav-label">스텝1</span></a>
                </li>
                <li class="nav-submenu__item<%= "workflow-step2".equals(sidebarActive) ? " active" : "" %>">
                    <a href="/user/workflow/step2"><span class="nav-label">스텝2</span></a>
                </li>
                <li class="nav-submenu__item<%= "workflow-step3".equals(sidebarActive) ? " active" : "" %>">
                    <a href="/user/workflow/step3"><span class="nav-label">스텝3</span></a>
                </li>
                <li class="nav-submenu__item<%= "workflow-ends".equals(sidebarActive) ? " active" : "" %>">
                    <a href="/user/workflow/ends"><span class="nav-label">종료</span></a>
                </li>
            </ul>
        </li>

        <!-- 저장소끌어오기 — 최상위 메뉴(워크플로우 하위 메뉴에 넣지 않는다: workflow.spec.ts 가 하위 항목 4개를 센다) -->
        <li class="nav-menu__item<%= "repositories".equals(sidebarActive) ? " active" : "" %>" id="navRepositories">
            <a href="/user/repositories">
                <svg class="nav-icon" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M3 2.5h7.5A1.5 1.5 0 0 1 12 4v5" stroke="currentColor" stroke-width="1.3"
                          stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M3 2.5v10A1.5 1.5 0 0 0 4.5 14H7" stroke="currentColor" stroke-width="1.3"
                          stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M12 10.5v4M10 12.5l2 2 2-2" stroke="currentColor" stroke-width="1.3"
                          stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span class="nav-label">저장소끌어오기</span>
            </a>
            <span class="tooltip">저장소끌어오기</span>
        </li>
    </ul>
</aside>

<script>
    /* 워크플로우 그룹 펼침/접힘. 사이드바 접힘 상태(sidebar_collapsed)와는 별개 키로 보관한다. */
    (function () {
        let group   = document.getElementById('navWorkflowGroup');
        let toggle  = document.getElementById('navWorkflowToggle');
        let sidebar = document.getElementById('sidebar');
        let STORAGE_KEY = 'nav_workflow_open';

        /* 워크플로우 화면이면 서버가 이미 is-open 을 찍어 둔다(활성 항목이 접혀 가려지지 않도록).
           그 외 화면에서는 직전에 사용자가 펼쳐 둔 상태를 복원한다. */
        if (!group.classList.contains('is-open') && sessionStorage.getItem(STORAGE_KEY) === 'true') {
            group.classList.add('is-open');
        }

        function syncAria() {
            toggle.setAttribute('aria-expanded', group.classList.contains('is-open') ? 'true' : 'false');
        }
        syncAria();

        toggle.addEventListener('click', function (e) {
            e.preventDefault();

            /* 접힌 사이드바에는 하위 메뉴가 들어갈 자리가 없다. 먼저 펼친 뒤 하위 메뉴를 연다. */
            if (sidebar.classList.contains('collapsed')) {
                sidebar.classList.remove('collapsed');
                sessionStorage.setItem('sidebar_collapsed', 'false');
                group.classList.add('is-open');
            } else {
                group.classList.toggle('is-open');
            }

            sessionStorage.setItem(STORAGE_KEY, group.classList.contains('is-open') ? 'true' : 'false');
            syncAria();
        });
    })();
</script>
