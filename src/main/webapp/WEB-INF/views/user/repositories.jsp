<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="org.springframework.web.util.HtmlUtils" %>
<%@ page import="kr.co.jihun.guisample.dto.SessionUser" %>
<%
    /* 상단바 표시 이름 — projects.jsp 와 같다. EL 은 자동 이스케이프가 없으므로 직접 이스케이프한다. */
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
    <title>저장소끌어오기 — 기술테스트 시스템</title>
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
    <% String activeMenu = "repositories"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Repositories</p>
            <h1 class="page-header__title">저장소끌어오기</h1>
            <p class="page-header__desc">저장소의 브랜치 최신 커밋 파일 목록을 조회합니다. 폴더를 누르면 하위로 이동합니다.</p>
        </header>

        <!-- 조회 조건 -->
        <form id="repoSearchForm" class="search-form" onsubmit="return false;">
            <label for="searchRepoUrl" class="search-form__label">저장소URL</label>
            <input type="text" id="searchRepoUrl" name="repoUrl"
                   class="search-form__input repo-url-input"
                   placeholder="https://github.com/owner/repo.git" maxlength="500">
            <label for="searchBranch" class="search-form__label">브랜치명</label>
            <input type="text" id="searchBranch" name="branch"
                   class="search-form__input" placeholder="비우면 기본 브랜치" maxlength="255">
            <div class="search-form__actions">
                <%-- 끌어오기 — GET /user/repository/files 로 브랜치 최신 커밋의 전체 파일 트리를 받는다. --%>
                <button type="button" id="btnRepoPull" class="search-form__btn search-form__btn--gray">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M8 2.5v8M4.5 7 8 10.5 11.5 7" stroke="currentColor" stroke-width="1.5"
                              stroke-linecap="round" stroke-linejoin="round"/>
                        <path d="M3 13.5h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    </svg>
                    <span id="btnRepoPullLabel">끌어오기</span>
                </button>
                <%-- 점검완료 — 이번 범위에서는 배치만 한다(동작 없음, 설계 §12). --%>
                <button type="button" id="btnInspectDone" class="search-form__btn">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="m3.5 8.5 3 3 6-7" stroke="currentColor" stroke-width="1.6"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    점검완료
                </button>
            </div>
        </form>

        <!-- 최신 커밋 바 (끌어오기 성공 후 표시) -->
        <div id="repoCommitBar" class="repo-commit-bar" hidden>
            <span class="repo-commit-bar__branch" id="repoCommitBranch"></span>
            <span class="repo-commit-bar__sha" id="repoCommitSha"></span>
            <span class="repo-commit-bar__message" id="repoCommitMessage"></span>
            <span class="repo-commit-bar__date" id="repoCommitDate"></span>
            <span class="repo-commit-bar__warn" id="repoCommitWarn" hidden></span>
        </div>

        <!-- 경로 표시 (루트 = 저장소 이름) -->
        <nav id="repoBreadcrumb" class="repo-breadcrumb" aria-label="현재 경로" hidden></nav>

        <!-- jqGrid -->
        <div class="grid-wrap">
            <table id="repoFileGrid"></table>
            <div id="repoFileGridPager"></div>
        </div>

    </main>
</div>

<%-- 알림 대화상자 — 기본 alert() 대신 쓰는 레이어 팝업(동작은 /js/dialog.js) --%>
<%@ include file="/WEB-INF/views/common/dialog.jsp" %>

<!-- jQuery / jqGrid -->
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/jquery.jqgrid.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/i18n/grid.locale-kr.min.js"></script>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>
<script src="${pageContext.request.contextPath}/js/dialog.js"></script>

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

    /*
     * 저장소끌어오기.
     *
     * GET /user/repository/files 응답 (성공·실패 모두 200)
     *   성공: { success:true, page, total, records, rows[], summary{} }
     *   실패: { success:false, message, page, total:0, records:0, rows:[] }
     *   rows[]  : path, name, parentPath(루트 = ""), type(DIR|FILE|SYMLINK|SUBMODULE), size(FILE 만 숫자), depth, webUrl(SUBMODULE 은 null)
     *   summary : repoUrl, owner, repo, branch, defaultBranch, commitId, commitMessage, commitDate(ISO-8601 UTC), truncated, entryCount
     *
     * 전체 트리를 한 번에 받아 브라우저에 보관하고, 폴더 이동은 클라이언트에서만 처리한다(추가 서버 호출 없음).
     * 파일명·커밋 메시지는 저장소 작성자가 정한 외부 문자열이다 — HTML 로 조립할 때는 escapeHtml, DOM 에는 textContent.
     */
    $(function () {
        let filesUrl = '${pageContext.request.contextPath}/user/repository/files';

        let UP_ROW_ID     = '__up__';   // 현재 폴더 맨 앞의 가상 ".." 행
        let EMPTY_INITIAL = '저장소URL 을 입력하고 끌어오기를 누르세요.';
        let EMPTY_FOLDER  = '파일이 없습니다.';
        let TYPE_LABEL    = { DIR: '폴더', FILE: '파일', SYMLINK: '링크', SUBMODULE: '서브모듈' };

        let childrenMap = {};    // parentPath → 그리드 행 배열 (서버 정렬 순서 그대로)
        let cwd         = '';    // 현재 폴더 경로 (루트 = "")
        let rootName    = '';    // 경로 표시 루트 = summary.repo

        let $grid = $('#repoFileGrid');

        function escapeHtml(value) {
            return String(value == null ? '' : value)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;');
        }

        /* 서버가 만든 GitHub 링크만 쓴다. 다른 스킴(javascript: 등)은 링크로 만들지 않는다. */
        function safeWebUrl(url) {
            return (typeof url === 'string' && /^https?:\/\//i.test(url)) ? url : null;
        }

        let ICON_DIR =
            '<svg class="repo-file__icon repo-file__icon--dir" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">' +
            '<path d="M1.5 4A1.5 1.5 0 0 1 3 2.5h3.2a1.5 1.5 0 0 1 1.06.44l.8.8a.5.5 0 0 0 .36.16H13A1.5 1.5 0 0 1 14.5 5.4V12A1.5 1.5 0 0 1 13 13.5H3A1.5 1.5 0 0 1 1.5 12V4Z"/></svg>';
        let ICON_FILE =
            '<svg class="repo-file__icon repo-file__icon--file" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
            '<path d="M4 1.5h5L12.5 5v9a.5.5 0 0 1-.5.5H4a.5.5 0 0 1-.5-.5V2a.5.5 0 0 1 .5-.5Z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>' +
            '<path d="M9 1.5V5h3.5" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>';

        /* 이름 셀 — 폴더는 하위 이동 링크, 파일은 GitHub 파일 페이지(새 탭), ".." 는 상위 이동. */
        function nameFormatter(cellValue, options, row) {
            if (row.rid === UP_ROW_ID) {
                return '<a href="#" class="repo-file__up">' + ICON_DIR + '<span>..</span></a>';
            }

            let name = escapeHtml(row.name);

            if (row.type === 'DIR') {
                return '<a href="#" class="repo-file__dir" data-path="' + escapeHtml(row.path) + '">'
                    + ICON_DIR + '<span>' + name + '</span></a>';
            }

            /* 서브모듈은 트리 안에 하위 항목이 없으므로(다른 저장소) 이동 링크를 만들지 않는다. */
            if (row.type === 'SUBMODULE') {
                return '<span class="repo-file__plain">' + ICON_DIR + '<span>' + name + '</span></span>';
            }

            let url = safeWebUrl(row.webUrl);
            if (!url) {
                return '<span class="repo-file__plain">' + ICON_FILE + '<span>' + name + '</span></span>';
            }
            return '<a class="repo-file__file" href="' + escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">'
                + ICON_FILE + '<span>' + name + '</span></a>';
        }

        function typeFormatter(cellValue, options, row) {
            if (row.rid === UP_ROW_ID) return '';
            return escapeHtml(TYPE_LABEL[cellValue] || cellValue || '');
        }

        /* 크기 — 파일만 표시 (B / KB / MB). */
        function sizeFormatter(cellValue, options, row) {
            if (row.type !== 'FILE' || cellValue == null || isNaN(cellValue)) return '';
            let n = Number(cellValue);
            if (n < 1024) return n + ' B';
            if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
            return (n / (1024 * 1024)).toFixed(1) + ' MB';
        }

        $grid.jqGrid({
            datatype:  'local',
            data:      [],
            colNames:  ['행 ID', '경로', '이름', '종류', '크기'],
            colModel: [
                { name: 'rid',  key: true, hidden: true },
                { name: 'path', hidden: true },
                { name: 'name', width: 460, sortable: false, formatter: nameFormatter },
                { name: 'type', width: 90,  sortable: false, align: 'center', formatter: typeFormatter },
                { name: 'size', width: 110, sortable: false, align: 'right',  formatter: sizeFormatter }
            ],
            pager:        '#repoFileGridPager',
            rownumbers:   true,
            rownumWidth:  56,
            rowNum:       100,
            rowList:      [100, 300, 1000],
            viewrecords:  true,
            autowidth:    true,
            height:       '480',
            caption:      '소스코드',
            emptyrecords: EMPTY_INITIAL
        });

        /* ── 폴더 탐색 ── */

        function parentOf(path) {
            let i = path.lastIndexOf('/');
            return i < 0 ? '' : path.substring(0, i);
        }

        /* 응답 rows 를 parentPath 로 묶어 둔다. 행 id 는 경로가 아니라 순번이다(경로의 특수문자가 DOM id 로 가지 않게). */
        function buildChildrenMap(rows) {
            let map = {};
            rows.forEach(function (r, i) {
                let parent = r.parentPath || '';
                (map[parent] = map[parent] || []).push({
                    rid:    'r' + i,
                    path:   r.path,
                    name:   r.name,
                    type:   r.type,
                    size:   r.size,
                    webUrl: r.webUrl
                });
            });
            return map;
        }

        function renderGrid(rows) {
            $grid.jqGrid('clearGridData');
            $grid.jqGrid('setGridParam', { data: rows, page: 1 }).trigger('reloadGrid');
        }

        function showFolder(path) {
            cwd = path || '';
            let rows = (childrenMap[cwd] || []).slice();
            if (cwd !== '') {
                rows.unshift({ rid: UP_ROW_ID, path: parentOf(cwd), name: '..', type: '', size: null, webUrl: null });
            }
            $grid.jqGrid('setGridParam', { emptyrecords: EMPTY_FOLDER });
            renderGrid(rows);
            renderBreadcrumb();
        }

        /* 경로 표시 — 루트(저장소 이름) / 세그먼트. 마지막(현재 폴더)은 링크가 아니다. textContent 로만 채운다. */
        function renderBreadcrumb() {
            let nav = document.getElementById('repoBreadcrumb');
            nav.textContent = '';

            let crumbs = [{ label: rootName || '/', path: '' }];
            if (cwd !== '') {
                let acc = '';
                cwd.split('/').forEach(function (seg) {
                    acc = acc ? acc + '/' + seg : seg;
                    crumbs.push({ label: seg, path: acc });
                });
            }

            crumbs.forEach(function (c, i) {
                if (i > 0) {
                    let sep = document.createElement('span');
                    sep.className = 'repo-breadcrumb__sep';
                    sep.textContent = '/';
                    nav.appendChild(sep);
                }
                let el;
                if (i === crumbs.length - 1) {
                    el = document.createElement('span');
                    el.className = 'repo-breadcrumb__current';
                    el.setAttribute('aria-current', 'page');
                } else {
                    el = document.createElement('a');
                    el.className = 'repo-breadcrumb__item';
                    el.href = '#';
                    el.setAttribute('data-path', c.path);
                }
                el.textContent = c.label;
                nav.appendChild(el);
            });

            nav.hidden = false;
        }

        $grid.on('click', '.repo-file__dir', function (e) {
            e.preventDefault();
            showFolder($(this).attr('data-path'));
        });
        $grid.on('click', '.repo-file__up', function (e) {
            e.preventDefault();
            showFolder(parentOf(cwd));
        });
        $('#repoBreadcrumb').on('click', '.repo-breadcrumb__item', function (e) {
            e.preventDefault();
            showFolder($(this).attr('data-path'));
        });

        /* ── 최신 커밋 바 ── */

        function pad2(n) { return (n < 10 ? '0' : '') + n; }

        /* ISO-8601(UTC) → 로컬 "yyyy-MM-dd HH:mm" */
        function formatCommitDate(iso) {
            if (!iso) return '';
            let d = new Date(iso);
            if (isNaN(d.getTime())) return String(iso);
            return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
                + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
        }

        function renderCommitBar(s) {
            $('#repoCommitBranch').text(s.branch || '');
            $('#repoCommitSha').text((s.commitId || '').substring(0, 7));
            $('#repoCommitMessage').text(String(s.commitMessage || '').split('\n')[0])
                                   .attr('title', s.commitMessage || '');
            $('#repoCommitDate').text(formatCommitDate(s.commitDate));

            let warn = document.getElementById('repoCommitWarn');
            if (s.truncated) {
                warn.textContent = '항목이 너무 많아 목록 일부만 표시합니다 (표시 ' + (s.entryCount || 0) + '건, 나머지는 생략).';
                warn.hidden = false;
            } else {
                warn.textContent = '';
                warn.hidden = true;
            }
            document.getElementById('repoCommitBar').hidden = false;
        }

        function resetView() {
            childrenMap = {};
            cwd = '';
            rootName = '';
            document.getElementById('repoCommitBar').hidden = true;
            document.getElementById('repoBreadcrumb').hidden = true;
            $grid.jqGrid('setGridParam', { emptyrecords: EMPTY_INITIAL });
            renderGrid([]);
        }

        /* ── 끌어오기 ── */

        function pullRepository() {
            let repoUrl = $.trim($('#searchRepoUrl').val());
            let branch  = $.trim($('#searchBranch').val());

            if (repoUrl === '') {
                appDialog.warn('저장소URL 을 입력하세요.', {
                    onClose: function () { $('#searchRepoUrl').trigger('focus'); }
                });
                return;
            }

            let $btn = $('#btnRepoPull');
            if ($btn.prop('disabled')) return;   // 요청 중 Enter 중복 방지
            $btn.prop('disabled', true);
            $('#btnRepoPullLabel').text('끌어오는 중…');

            $.ajax({
                url:      filesUrl,
                type:     'GET',
                dataType: 'json',
                cache:    false,
                data:     { repoUrl: repoUrl, branch: branch }
            }).done(function (data) {
                if (data && data.success) {
                    let s = data.summary || {};
                    childrenMap = buildChildrenMap(data.rows || []);
                    rootName = s.repo || '';
                    renderCommitBar(s);
                    showFolder('');
                } else {
                    resetView();
                    appDialog.warn('끌어오기에 실패했습니다.\n' + ((data && data.message) || ''),
                                   { title: '저장소끌어오기' });
                }
            })
            .fail(function (xhr, status, error) {
                /* 401/403 은 csrf.js 가 새로고침으로 로그인에 돌려보낸다. */
                if (xhr.status === 401 || xhr.status === 403) return;
                resetView();
                appDialog.warn('끌어오기 중 오류가 발생했습니다.\n' + status + ' : ' + error,
                               { title: '저장소끌어오기' });
            })
            .always(function () {
                $btn.prop('disabled', false);
                $('#btnRepoPullLabel').text('끌어오기');
            });
        }

        $('#btnRepoPull').on('click', pullRepository);
        $('#searchRepoUrl, #searchBranch').on('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                pullRepository();
            }
        });

        /* #btnInspectDone(점검완료)은 이번 범위에서 배치만 한다 — 핸들러 없음(설계 §12). */
    });
</script>
</body>
</html>
