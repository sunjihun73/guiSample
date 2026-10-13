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
    <title>프로젝트목록 — 기술테스트 시스템</title>
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
    <% String activeMenu = "projects"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Projects</p>
            <h1 class="page-header__title">프로젝트 목록</h1>
            <p class="page-header__desc">등록된 프로젝트를 조회하고 관리합니다.</p>
        </header>

        <!-- 조회 조건 -->
        <form id="searchForm" class="search-form" onsubmit="return false;">
            <label for="searchProjectName" class="search-form__label">프로젝트명</label>
            <input type="text" id="searchProjectName" name="projectName"
                   class="search-form__input" placeholder="프로젝트명을 입력하세요">
            <div class="search-form__actions">
                <button type="button" id="btnSearch" class="search-form__btn search-form__btn--ghost">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <circle cx="7.2" cy="7.2" r="4.2" stroke="currentColor" stroke-width="1.5"/>
                        <path d="m10.4 10.4 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    </svg>
                    조회
                </button>
                <button type="button" id="btnCreate" class="search-form__btn">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                    </svg>
                    등록
                </button>
                <%-- REPO동기화 — 각 프로젝트의 repo_url 저장소에서 최근 커밋 중 {req_id} 가 일치하는
                     커밋의 변경 파일로 file_names 를 갱신한다 (POST /user/project/projects/repo-sync). --%>
                <button type="button" id="btnRepoSync" class="search-form__btn search-form__btn--gray">
                    <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M13 8a5 5 0 0 1-8.6 3.5M3 8a5 5 0 0 1 8.6-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        <path d="M11.8 2v2.6H9.2M4.2 14v-2.6h2.6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    REPO동기화
                </button>
            </div>
        </form>

        <!-- jqGrid -->
        <div class="grid-wrap">
            <table id="projectGrid"></table>
            <div id="projectGridPager"></div>
        </div>

    </main>
</div>

<!-- ── 프로젝트 등록 레이어 팝업 ── -->
<div class="layer-popup" id="projectPopup" aria-hidden="true">
    <div class="layer-popup__dim" id="projectPopupDim"></div>
    <div class="layer-popup__panel" role="dialog" aria-modal="true" aria-labelledby="projectPopupTitle">
        <div class="layer-popup__header">
            <h2 class="layer-popup__title" id="projectPopupTitle">프로젝트 등록</h2>
            <p class="layer-popup__desc" id="projectPopupDesc">프로젝트 정보를 입력한 뒤 저장하세요.</p>
            <button type="button" class="layer-popup__close" id="btnPopupClose" aria-label="닫기">
                <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
            </button>
        </div>
        <div class="layer-popup__body">
            <div class="form-field" id="popupProjectIdField" style="display:none;">
                <label for="popupProjectId" class="form-field__label">프로젝트 ID</label>
                <input type="text" id="popupProjectId" class="form-field__input"
                       readonly tabindex="-1">
            </div>
            <div class="form-field">
                <label for="popupProjectName" class="form-field__label">프로젝트명</label>
                <input type="text" id="popupProjectName" class="form-field__input"
                       placeholder="프로젝트명을 입력하세요" maxlength="200">
            </div>
            <div class="form-field">
                <label for="popupProjectOwnerName" class="form-field__label">담당자</label>
                <%-- 담당자는 등록 시 로그인 사용자로 고정되며 등록·수정 모두 변경 불가.
                     서버도 요청 값을 쓰지 않는다(등록: 세션 사용자명 저장, 수정: SET 대상 아님). --%>
                <input type="text" id="popupProjectOwnerName" class="form-field__input"
                       data-login-name="<%= loginDisplayName %>" readonly tabindex="-1">
            </div>
            <%-- maxlength 는 project_master 컬럼 길이와 맞춘다 (req_id 100, repo_url 500, file_names text). --%>
            <div class="form-field">
                <label for="popupReqId" class="form-field__label">요청 ID</label>
                <input type="text" id="popupReqId" class="form-field__input"
                       placeholder="요청 ID를 입력하세요" maxlength="100">
            </div>
            <div class="form-field">
                <label for="popupRepoUrl" class="form-field__label">저장소 URL</label>
                <input type="text" id="popupRepoUrl" class="form-field__input"
                       placeholder="저장소 URL을 입력하세요" maxlength="500">
            </div>
            <div class="form-field">
                <label for="popupFileNames" class="form-field__label">파일명</label>
                <textarea id="popupFileNames" class="form-field__input form-field__input--textarea"
                          rows="3" placeholder="파일명을 입력하세요"></textarea>
            </div>
            <div class="form-field">
                <label for="popupProjectDescription" class="form-field__label">프로젝트 설명</label>
                <textarea id="popupProjectDescription" name="popupProjectDescription"
                          placeholder="프로젝트 설명을 입력하세요"></textarea>
            </div>
        </div>
        <div class="layer-popup__footer">
            <button type="button" id="btnPopupCancel" class="search-form__btn search-form__btn--ghost">취소</button>
            <button type="button" id="btnPopupSave"   class="search-form__btn">저장</button>
        </div>
    </div>
</div>

<%-- 알림 대화상자 — 기본 alert() 대신 쓰는 레이어 팝업(동작은 /js/dialog.js) --%>
<%@ include file="/WEB-INF/views/common/dialog.jsp" %>

<!-- jQuery / jqGrid -->
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/jquery.jqgrid.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/i18n/grid.locale-kr.min.js"></script>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>
<script src="${pageContext.request.contextPath}/js/dialog.js"></script>

<!-- TinyMCE 6.8.6 (로컬 static 서빙, MIT) -->
<script src="${pageContext.request.contextPath}/tinymce/tinymce.min.js"></script>

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
        let listUrl = '${pageContext.request.contextPath}/user/project/projects';

        /* ── 프로젝트 설명 TinyMCE (팝업 최초 오픈 시 1회 초기화 후 재사용) ── */
        let TINYMCE_BASE = '${pageContext.request.contextPath}/tinymce';

        let descEditor        = null;    // 초기화 완료된 TinyMCE Editor 인스턴스
        let descEditorLoading = false;   // 중복 초기화 방지
        let pendingDescHtml   = '';      // 에디터 초기화 완료 전 적용 대기 HTML

        /* 팝업이 display:none 인 동안 초기화하면 iframe 크기 계산이 깨지므로
           팝업이 열린(is-open) 뒤 최초 1회만 초기화한다. */
        function ensureDescEditor() {
            if (descEditor || descEditorLoading) return;
            descEditorLoading = true;

            tinymce.init({
                selector:     '#popupProjectDescription',
                language:     'ko_KR',
                language_url: TINYMCE_BASE + '/langs/ko_KR.js',
                height:       300,
                menubar:      false,
                statusbar:    false,
                branding:     false,
                promotion:    false,
                convert_urls: false,
                plugins:      'lists link table autolink searchreplace charmap',
                content_style: 'body { font-family: Inter, ui-sans-serif, system-ui, -apple-system, ' +
                               '"Apple SD Gothic Neo", "Noto Sans KR", sans-serif; font-size: 14px; ' +
                               'line-height: 1.6; color: #0f172a; }',
                toolbar:      'undo redo | blocks fontsize | bold italic underline strikethrough forecolor backcolor | ' +
                              'alignleft aligncenter alignright | bullist numlist | table link charmap | removeformat'
            }).then(function (editors) {
                descEditor = editors[0];
                descEditor.setContent(pendingDescHtml);
            });
        }

        /* 에디터 내용 전체 교체 (초기화 전이면 대기시켰다가 init 완료 시 반영) */
        function setDescHtml(html) {
            pendingDescHtml = html || '';
            if (descEditor) {
                descEditor.setContent(pendingDescHtml);
            }
        }

        /* 에디터 내용 조회 — 초기화 완료 전이면 마지막 setDescHtml 값을 반환해
           수정 모드의 설명 유실을 막는다. */
        function getDescHtml() {
            if (!descEditor) return pendingDescHtml;
            return descEditor.getContent();
        }

        // yyyy-mm-dd hh:mm:ss 형태로 변환 (DB 타임스탬프 문자열의 앞 19자리 사용)
        function dateTimeFormatter(cellValue) {
            if (!cellValue) {
                return "";
            }
            return String(cellValue).replace("T", " ").substring(0, 19);
        }

        // "상세" 버튼 렌더링 (rowId 는 projectId)
        function detailButtonFormatter(cellValue, options) {
            return '<button type="button" class="search-form__btn btn-detail" data-id="'
                + options.rowId + '">상세</button>';
        }

        $("#projectGrid").jqGrid({
            url:       listUrl,
            datatype:  "json",
            mtype:     "GET",
            colNames:  ["프로젝트 ID", "상세", "프로젝트명", "담당자", "요청 ID", "생성일", "생성자", "수정일", "수정자"],
            colModel: [
                { name: "projectId",        index: "projectId",        width: 120, align: "center", hidden : true },
                { name: "detail",           width: 70,  align: "center", sortable: false, search: false, formatter: detailButtonFormatter },
                { name: "projectName",      index: "projectName",      width: 240 },
                { name: "projectOwnerName", index: "projectOwnerName", width: 120, align: "center" },
                { name: "reqId",            index: "reqId",            width: 120, align: "center" },
                { name: "createDate",       index: "createDate",       width: 160, align: "center", formatter: dateTimeFormatter },
                { name: "createUserId",     index: "createUserId",     width: 100, align: "center" },
                { name: "updateDate",       index: "updateDate",       width: 160, align: "center", formatter: dateTimeFormatter },
                { name: "updateUserId",     index: "updateUserId",     width: 100, align: "center" }
            ],
            jsonReader: {
                root:        "rows",
                page:        "page",
                total:       "total",
                records:     "records",
                repeatitems: false,
                id:          "projectId"
            },
            pager:        "#projectGridPager",
            rownumbers:   true,
            rownumWidth:  56,
            rowNum:       10,
            rowList:      [10, 20, 50, 100],
            sortname:     "createDate",
            sortorder:    "desc",
            viewrecords:  true,
            autowidth:    true,
            height:       "420",
            caption:      "프로젝트",
            emptyrecords: "조회된 데이터가 없습니다.",
            loadError: function (xhr, status, error) {
                appDialog.warn("목록 조회 중 오류가 발생했습니다.\n" + status + " : " + error);
            }
        });

        function reloadGrid() {
            $("#projectGrid").jqGrid("setGridParam", {
                postData: { projectName: $("#searchProjectName").val() },
                page:     1
            }).trigger("reloadGrid");
        }

        $("#btnSearch").on("click", reloadGrid);

        $("#searchProjectName").on("keydown", function (e) {
            if (e.key === "Enter") {
                e.preventDefault();
                reloadGrid();
            }
        });

        /* ── 프로젝트 등록/수정 레이어 팝업 ── */
        let saveUrl   = '${pageContext.request.contextPath}/user/project/projects';
        let $popup    = $("#projectPopup");
        let popupMode = "create";   // "create" | "edit"

        function openCreatePopup() {
            popupMode = "create";
            $("#projectPopupTitle").text("프로젝트 등록");
            $("#projectPopupDesc").text("프로젝트 정보를 입력한 뒤 저장하세요.");
            $("#popupProjectIdField").hide();
            $("#popupProjectId").val("");
            $("#popupProjectName").val("");
            $("#popupReqId").val("");
            $("#popupRepoUrl").val("");
            $("#popupFileNames").val("");
            // 담당자 = 현재 로그인 사용자 (표시용 — 실제 저장값은 서버가 세션에서 정한다)
            $("#popupProjectOwnerName").val($("#popupProjectOwnerName").attr("data-login-name"));
            $popup.addClass("is-open").attr("aria-hidden", "false");
            ensureDescEditor();     // 팝업이 보이는 상태에서 생성해야 크기 계산이 정상
            setDescHtml("");
            $("#popupProjectName").trigger("focus");
        }

        // project: 단건 조회 API(GET /projects/{id})의 project 객체
        function openEditPopup(project) {
            popupMode = "edit";
            $("#projectPopupTitle").text("프로젝트 수정");
            $("#projectPopupDesc").text("변경할 내용을 수정한 뒤 저장하세요.");
            // projectId 항목은 화면에 노출하지 않고(숨김 유지), 값만 채워 수정 시 PATCH URL 식별자로 사용한다.
            $("#popupProjectIdField").hide();
            $("#popupProjectId").val(project.projectId);
            $("#popupProjectName").val(project.projectName);
            $("#popupProjectOwnerName").val(project.projectOwnerName);
            $("#popupReqId").val(project.reqId || "");
            $("#popupRepoUrl").val(project.repoUrl || "");
            $("#popupFileNames").val(project.fileNames || "");
            $popup.addClass("is-open").attr("aria-hidden", "false");
            ensureDescEditor();     // 팝업이 보이는 상태에서 생성해야 크기 계산이 정상
            setDescHtml(project.projectDescription || "");
            $("#popupProjectName").trigger("focus");
        }

        function closePopup() {
            $popup.removeClass("is-open").attr("aria-hidden", "true");
        }

        function saveProject() {
            let projectName = $.trim($("#popupProjectName").val());

            if (projectName === "") {
                // 대화상자는 스크립트를 멈추지 않으므로 포커스 이동은 닫힌 뒤(onClose)에 한다.
                appDialog.warn("프로젝트명을 입력하세요.", {
                    onClose: function () { $("#popupProjectName").trigger("focus"); }
                });
                return;
            }

            let ajaxUrl, ajaxType;
            if (popupMode === "edit") {
                ajaxUrl  = saveUrl + "/" + encodeURIComponent($("#popupProjectId").val());
                ajaxType = "PATCH";
            } else {
                ajaxUrl  = saveUrl;
                ajaxType = "POST";
            }

            $("#btnPopupSave").prop("disabled", true);

            $.ajax({
                url:         ajaxUrl,
                type:        ajaxType,
                contentType: "application/json",
                dataType:    "json",
                data:        JSON.stringify({
                    projectName:        projectName,
                    projectDescription: getDescHtml(),
                    reqId:              $.trim($("#popupReqId").val()),
                    repoUrl:            $.trim($("#popupRepoUrl").val()),
                    fileNames:          $("#popupFileNames").val()
                })
            }).done(function (data) {
                if (data && data.success) {
                    closePopup();
                    $("#projectGrid").trigger("reloadGrid");
                } else {
                    appDialog.warn("저장에 실패했습니다.\n" + ((data && data.message) || ""));
                }
            })
            .fail(function (xhr, status, error) {
                appDialog.warn("저장 중 오류가 발생했습니다.\n" + status + " : " + error);
            })
            .always(function () {
                $("#btnPopupSave").prop("disabled", false);
            });
        }

        // "상세" 버튼 클릭 → 단건 조회 후 수정 팝업 오픈
        // (projectDescription은 HTML이라 그리드 셀에 싣지 않고 매번 단건 API로 로드.
        //  조회 실패 시 팝업을 열지 않음 — UPDATE가 description을 무조건 SET 하므로
        //  미로드 상태로 저장하면 기존 설명이 빈 값으로 덮어써지는 유실이 생긴다.)
        $("#projectGrid").on("click", ".btn-detail", function () {
            let $btn  = $(this);
            let rowId = $btn.data("id");

            $btn.prop("disabled", true);

            $.getJSON(saveUrl + "/" + encodeURIComponent(rowId))
                .done(function (data) {
                    if (data && data.success) {
                        openEditPopup(data.project);
                    } else {
                        appDialog.warn("프로젝트 상세 조회에 실패했습니다.\n" + ((data && data.message) || ""));
                    }
                })
                .fail(function (xhr, status, error) {
                    appDialog.warn("프로젝트 상세 조회 중 오류가 발생했습니다.\n" + status + " : " + error);
                })
                .always(function () {
                    $btn.prop("disabled", false);
                });
        });

        /* ── REPO동기화 ── */
        let repoSyncUrl = saveUrl + "/repo-sync";

        // 서버 결과(RepoSyncResult)를 대화상자 한 장으로 요약한다. 실패 건은 사유까지 보여 준다.
        function repoSyncSummary(r) {
            if (r.total === 0) {
                return "동기화 대상 프로젝트가 없습니다.\n(저장소 URL 과 요청 ID 가 모두 입력된 프로젝트만 대상입니다.)";
            }
            let lines = [
                "REPO동기화 완료 (최근 " + r.lookbackDays + "일 커밋 기준)",
                "",
                "대상 " + r.total + "건 / 갱신 " + r.updated + "건 / 변경없음 " + r.unchanged
                    + "건 / 일치커밋없음 " + r.noMatch + "건 / 실패 " + r.failed + "건"
            ];
            r.items.filter(function (it) { return it.status === "FAILED"; })
                .forEach(function (it) {
                    lines.push("- [실패] " + it.projectName + " (" + it.reqId + "): " + (it.message || ""));
                });
            return lines.join("\n");
        }

        function syncRepository() {
            let $btn = $("#btnRepoSync");
            $btn.prop("disabled", true);

            $.ajax({
                url:      repoSyncUrl,
                type:     "POST",
                dataType: "json"
            }).done(function (data) {
                if (data && data.success) {
                    // 실패 건이 섞여 있으면 경고 톤으로 띄워 눈에 띄게 한다.
                    let showDialog = data.result.failed > 0 ? appDialog.warn : appDialog.alert;
                    showDialog.call(appDialog, repoSyncSummary(data.result), { title: "REPO동기화" });
                    $("#projectGrid").trigger("reloadGrid");
                } else {
                    appDialog.warn("REPO동기화에 실패했습니다.\n" + ((data && data.message) || ""),
                                   { title: "REPO동기화" });
                }
            })
            .fail(function (xhr, status, error) {
                appDialog.warn("REPO동기화 중 오류가 발생했습니다.\n" + status + " : " + error,
                               { title: "REPO동기화" });
            })
            .always(function () {
                $btn.prop("disabled", false);
            });
        }

        $("#btnCreate").on("click", openCreatePopup);
        $("#btnRepoSync").on("click", syncRepository);
        $("#btnPopupCancel").on("click", closePopup);
        $("#btnPopupClose").on("click", closePopup);
        $("#projectPopupDim").on("click", closePopup);
        $("#btnPopupSave").on("click", saveProject);
    });
</script>

</body>
</html>