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
    <title>Embedding — 기술테스트 시스템</title>
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
    <% String activeMenu = "embedding"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content">

        <header class="page-header">
            <p class="page-header__eyebrow">Knowledge Base</p>
            <h1 class="page-header__title">Embedding</h1>
            <p class="page-header__desc">카테고리를 선택하고 텍스트 파일을 업로드해 지식베이스를 색인합니다.</p>
        </header>

        <div class="content-split">

            <!-- ── 좌측: 카테고리 목록 그리드 ── -->
            <div class="content-split__col">
                <div class="grid-header">
                    <div>
                        <span class="grid-header__title">카테고리 목록</span>
                        <p class="grid-header__desc">행을 선택하면 오른쪽 지식파일 목록이 갱신됩니다.</p>
                    </div>
                    <div class="search-form__actions">
                        <button type="button" id="btnCategoryRead" class="search-form__btn search-form__btn--ghost search-form__btn--sm">
                            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                            <circle cx="7.2" cy="7.2" r="4.2" stroke="currentColor" stroke-width="1.5"/>
                            <path d="m10.4 10.4 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        </svg>
                            조회
                        </button>
                        <button type="button" id="btnCategoryCreate" class="search-form__btn search-form__btn--sm">
                            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                            <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                        </svg>
                            등록
                        </button>
                    </div>
                </div>

                <div class="grid-wrap">
                    <table id="categoryGrid"></table>
                    <div id="categoryGridPager"></div>
                </div>
            </div>

            <!-- ── 우측: 지식파일 목록 그리드 ── -->
            <div class="content-split__col">
                <div class="grid-header">
                    <div>
                        <span class="grid-header__title">지식파일 목록</span>
                        <p class="grid-header__desc">선택된 카테고리에 색인된 파일입니다.</p>
                    </div>
                    <div class="search-form__actions">
                        <button type="button" id="btnKnowledgeRead"   class="search-form__btn search-form__btn--ghost search-form__btn--sm">
                            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                            <circle cx="7.2" cy="7.2" r="4.2" stroke="currentColor" stroke-width="1.5"/>
                            <path d="m10.4 10.4 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        </svg>
                            조회
                        </button>
                        <button type="button" id="btnKnowledgeCreate" class="search-form__btn search-form__btn--sm">
                            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                            <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                        </svg>
                            등록
                        </button>
                    </div>
                </div>

                <div class="grid-wrap">
                    <table id="knowledgeGrid"></table>
                    <div id="knowledgeGridPager"></div>
                </div>
            </div>

        </div>

    </main>
</div>

<!-- ── 카테고리 등록 레이어 팝업 ── -->
<div class="layer-popup" id="categoryPopup" aria-hidden="true">
    <div class="layer-popup__dim" id="categoryPopupDim"></div>
    <div class="layer-popup__panel" role="dialog" aria-modal="true" aria-labelledby="categoryPopupTitle">
        <div class="layer-popup__header">
            <h2 class="layer-popup__title" id="categoryPopupTitle">카테고리 등록</h2>
            <p class="layer-popup__desc">지식파일을 분류할 카테고리명을 입력하세요.</p>
            <button type="button" class="layer-popup__close" id="btnCategoryPopupClose" aria-label="닫기">
                <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
            </button>
        </div>
        <div class="layer-popup__body">
            <div class="form-field">
                <label for="popupCategoryName" class="form-field__label">카테고리명</label>
                <input type="text" id="popupCategoryName" class="form-field__input"
                       placeholder="카테고리명을 입력하세요" maxlength="200">
            </div>
        </div>
        <div class="layer-popup__footer">
            <button type="button" id="btnCategoryPopupCancel" class="search-form__btn search-form__btn--ghost">취소</button>
            <button type="button" id="btnCategoryPopupSave"   class="search-form__btn">저장</button>
        </div>
    </div>
</div>

<!-- ── 지식파일 등록(파일선택) 레이어 팝업 ── -->
<div class="layer-popup" id="knowledgePopup" aria-hidden="true">
    <div class="layer-popup__dim" id="knowledgePopupDim"></div>
    <div class="layer-popup__panel" role="dialog" aria-modal="true" aria-labelledby="knowledgePopupTitle">
        <div class="layer-popup__header">
            <h2 class="layer-popup__title" id="knowledgePopupTitle">지식파일 등록</h2>
            <p class="layer-popup__desc">선택한 카테고리에 텍스트 파일을 업로드해 임베딩합니다.</p>
            <button type="button" class="layer-popup__close" id="btnKnowledgePopupClose" aria-label="닫기">
                <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
            </button>
        </div>
        <div class="layer-popup__body">
            <div class="form-field">
                <label for="knowledgePopupCategory" class="form-field__label">선택된 카테고리</label>
                <input type="text" id="knowledgePopupCategory" class="form-field__input" readonly
                       placeholder="왼쪽 카테고리 목록에서 카테고리를 선택하세요">
            </div>

            <div class="form-field">
                <label for="knowledgeFile" class="form-field__label">텍스트 파일</label>
                <input type="file" id="knowledgeFile" class="embed-file" accept=".txt,.md,text/*">
                <p class="form-field__hint">.txt · .md 형식, 최대 10MB 까지 업로드할 수 있습니다.</p>
            </div>

            <div id="knowledgePopupResult" class="embed-result" role="status" aria-live="polite"></div>
        </div>
        <div class="layer-popup__footer">
            <button type="button" id="btnKnowledgePopupCancel" class="search-form__btn search-form__btn--ghost">취소</button>
            <button type="button" id="btnKnowledgePopupUpload" class="search-form__btn">업로드</button>
        </div>
    </div>
</div>

<!-- ── 청크 목록(상세) 레이어 팝업 ── -->
<div class="layer-popup" id="chunkPopup" aria-hidden="true">
    <div class="layer-popup__dim" id="chunkPopupDim"></div>
    <div class="layer-popup__panel layer-popup__panel--wide" role="dialog" aria-modal="true" aria-labelledby="chunkPopupTitle">
        <div class="layer-popup__header">
            <h2 class="layer-popup__title" id="chunkPopupTitle">청크 목록</h2>
            <p class="layer-popup__desc">지식파일이 분할되어 벡터로 저장된 단위입니다.</p>
            <button type="button" class="layer-popup__close" id="btnChunkPopupCloseX" aria-label="닫기">
                <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
            </button>
        </div>
        <div class="layer-popup__body">
            <div class="chunk-list-meta" id="chunkPopupMeta" role="status" aria-live="polite"></div>
            <div class="chunk-list-wrap">
                <table class="chunk-list">
                    <thead>
                        <tr>
                            <th style="width:64px;">순번</th>
                            <th>내용</th>
                        </tr>
                    </thead>
                    <tbody id="chunkListBody"></tbody>
                </table>
            </div>
        </div>
        <div class="layer-popup__footer">
            <button type="button" id="btnChunkPopupClose" class="search-form__btn search-form__btn--ghost">닫기</button>
        </div>
    </div>
</div>

<script>
    /* ── 사이드바 토글 (다른 페이지와 동일) ───────────────── */
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
</script>

<!-- jQuery / jqGrid (카테고리 목록 그리드용) -->
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/jquery.jqgrid.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/free-jqgrid/4.15.5/i18n/grid.locale-kr.min.js"></script>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>

<script>
    $(function () {
        let listUrl          = '${pageContext.request.contextPath}/user/category/categories';
        let knowledgeListUrl = '${pageContext.request.contextPath}/user/knowledge/knowledges';
        let chunkListUrl     = '${pageContext.request.contextPath}/user/knowledge/knowledges/chunks';
        let uploadUrl        = '${pageContext.request.contextPath}/user/rag/embedding';

        // 왼쪽 카테고리 그리드에서 선택된 행 (지식파일 업로드 시 사용)
        let selectedCategoryId   = null;
        let selectedCategoryName = null;

        // yyyy-mm-dd hh:mm:ss 형태로 변환 (DB 타임스탬프 문자열의 앞 19자리 사용)
        function dateTimeFormatter(cellValue) {
            if (!cellValue) {
                return "";
            }
            return String(cellValue).replace("T", " ").substring(0, 19);
        }

        // 지식파일 그리드 "상세" 버튼 렌더링 (rowId = fileId) — projects.jsp 와 동일 디자인
        function detailButtonFormatter(cellValue, options) {
            return '<button type="button" class="search-form__btn btn-detail" data-id="'
                + options.rowId + '">상세</button>';
        }

        // 페이지 로딩 시 GET /user/category/categories 로 목록 자동 조회
        $("#categoryGrid").jqGrid({
            url:       listUrl,
            datatype:  "json",
            mtype:     "GET",
            colNames:  ["카테고리 ID", "카테고리명", "생성일"],
            colModel: [
                { name: "categoryId",   index: "categoryId",   width: 120, align: "center", hidden: true },
                { name: "categoryName", index: "categoryName", width: 200 },
                { name: "createDate",   index: "createDate",   width: 160, align: "center", formatter: dateTimeFormatter }
            ],
            jsonReader: {
                root:        "rows",
                page:        "page",
                total:       "total",
                records:     "records",
                repeatitems: false,
                id:          "categoryId"
            },
            pager:        "#categoryGridPager",
            rownumbers:   true,
            rownumWidth:  56,
            rowNum:       10,
            rowList:      [10, 20, 50, 100],
            viewrecords:  true,
            autowidth:    true,
            height:       "520",
            emptyrecords: "조회된 데이터가 없습니다.",
            // 최초 로딩 시 첫 번째 행을 자동 선택 → onSelectRow 가 우측 지식파일 목록을 자동 조회
            loadComplete: function () {
                if (selectedCategoryId === null) {
                    let ids = $(this).jqGrid("getDataIDs");
                    if (ids && ids.length > 0) {
                        $(this).jqGrid("setSelection", ids[0], true);  // 2번째 인자 true → onSelectRow 트리거
                    }
                }
            },
            // 행 선택 시 categoryId/categoryName 보관 → 우측 지식파일 목록을 해당 카테고리로 조회
            onSelectRow: function (rowId) {
                let row = $("#categoryGrid").jqGrid("getRowData", rowId);
                selectedCategoryId   = rowId;                       // jsonReader.id = categoryId
                selectedCategoryName = (row && row.categoryName) ? row.categoryName : rowId;
                reloadKnowledgeGrid();                              // 선택한 category_id 기준으로 우측 목록 필터 조회
            },
            loadError: function (xhr, status, error) {
                alert("목록 조회 중 오류가 발생했습니다.\n" + status + " : " + error);
            }
        });

        // "조회" 클릭 → 카테고리 목록 조회 (jqGrid 내부 jQuery $.ajax GET)
        $("#btnCategoryRead").on("click", function () {
            $("#categoryGrid").jqGrid("setGridParam", {
                datatype: "json",
                url:      listUrl,
                page:     1
            }).trigger("reloadGrid");
        });

        /* ── 카테고리 등록 레이어 팝업 (projects.jsp 패턴) ── */
        let saveUrl = listUrl;   // POST /user/category/categories
        let $popup  = $("#categoryPopup");

        function openCreatePopup() {
            $("#popupCategoryName").val("");
            $popup.addClass("is-open").attr("aria-hidden", "false");
            $("#popupCategoryName").trigger("focus");
        }

        function closePopup() {
            $popup.removeClass("is-open").attr("aria-hidden", "true");
        }

        function saveCategory() {
            let categoryName = $.trim($("#popupCategoryName").val());

            if (categoryName === "") {
                alert("카테고리명을 입력하세요.");
                $("#popupCategoryName").trigger("focus");
                return;
            }

            $("#btnCategoryPopupSave").prop("disabled", true);

            $.ajax({
                url:         saveUrl,
                type:        "POST",
                contentType: "application/json",
                dataType:    "json",
                data:        JSON.stringify({ categoryName: categoryName })
            }).done(function (data) {
                if (data && data.success) {
                    closePopup();
                    $("#categoryGrid").jqGrid("setGridParam", {
                        datatype: "json",
                        url:      listUrl,
                        page:     1
                    }).trigger("reloadGrid");
                } else {
                    alert("저장에 실패했습니다.\n" + ((data && data.message) || ""));
                }
            })
            .fail(function (xhr, status, error) {
                alert("저장 중 오류가 발생했습니다.\n" + status + " : " + error);
            })
            .always(function () {
                $("#btnCategoryPopupSave").prop("disabled", false);
            });
        }

        $("#btnCategoryCreate").on("click", openCreatePopup);
        $("#btnCategoryPopupCancel").on("click", closePopup);
        $("#btnCategoryPopupClose").on("click", closePopup);
        $("#categoryPopupDim").on("click", closePopup);
        $("#btnCategoryPopupSave").on("click", saveCategory);

        /* ══════════════════════════════════════════════════════════
           지식파일 목록 그리드 + 파일선택(등록) 레이어 팝업
           - 목록: GET /user/knowledge/knowledges (jqGrid 표준 shape)
           - 업로드: POST /user/rag/embedding  multipart { file, categoryId }
                     → 200 JSON { success, fileId, categoryId, fileName, chunkCount, message }
           ══════════════════════════════════════════════════════════ */

        // 초기에는 fetch 하지 않는다(datatype:"local"). 좌측 카테고리 첫 행 자동 선택 →
        // reloadKnowledgeGrid 가 선택된 category_id 로 첫 조회를 수행하므로 전체 조회를 방지한다.
        $("#knowledgeGrid").jqGrid({
            url:       knowledgeListUrl,
            datatype:  "local",
            mtype:     "GET",
            colNames:  ["파일 ID", "상세", "카테고리명", "파일명", "생성일"],
            colModel: [
                { name: "fileId",       index: "fileId",       width: 120, align: "center", hidden: true },
                { name: "detail",                              width: 70,  align: "center", sortable: false, search: false, formatter: detailButtonFormatter },
                { name: "categoryName", index: "categoryName", width: 160, align: "left" },
                { name: "fileName",     index: "fileName",     width: 220 },
                { name: "createDate",   index: "createDate",   width: 160, align: "center", formatter: dateTimeFormatter }
            ],
            jsonReader: {
                root:        "rows",
                page:        "page",
                total:       "total",
                records:     "records",
                repeatitems: false,
                id:          "fileId"
            },
            pager:        "#knowledgeGridPager",
            rownumbers:   true,
            rownumWidth:  56,
            rowNum:       10,
            rowList:      [10, 20, 50, 100],
            viewrecords:  true,
            autowidth:    true,
            height:       "520",
            emptyrecords: "조회된 데이터가 없습니다.",
            loadError: function (xhr, status, error) {
                alert("지식파일 목록 조회 중 오류가 발생했습니다.\n" + status + " : " + error);
            }
        });

        function reloadKnowledgeGrid() {
            // categoryId 는 경로 변수(/knowledges/{categoryId})로 전달한다.
            // 선택된 카테고리가 없으면 조회하지 않는다.
            if (!selectedCategoryId) {
                return;
            }
            $("#knowledgeGrid").jqGrid("setGridParam", {
                datatype: "json",
                url:      knowledgeListUrl + "/" + encodeURIComponent(selectedCategoryId),
                page:     1,
                postData: {}
            }).trigger("reloadGrid");
        }

        // "조회" 클릭 → 지식파일 목록 재조회
        $("#btnKnowledgeRead").on("click", reloadKnowledgeGrid);

        /* ── 파일선택(등록) 레이어 팝업 ── */
        let $knowledgePopup = $("#knowledgePopup");

        // 결과 영역 텍스트 표시 — textContent 로만 삽입(XSS 방지)
        function showKnowledgeResult(text, state) {
            let el = document.getElementById("knowledgePopupResult");
            el.className = "embed-result" + (state ? " is-" + state : "");
            el.textContent = text;
        }

        function openKnowledgePopup() {
            // 왼쪽 카테고리 목록에서 행이 선택되어 있어야 업로드 가능
            if (!selectedCategoryId) {
                alert("왼쪽 '카테고리 목록'에서 카테고리를 먼저 선택하세요.");
                return;
            }
            $("#knowledgePopupCategory").val(selectedCategoryName);
            $("#knowledgeFile").val("");
            showKnowledgeResult("", null);
            $knowledgePopup.addClass("is-open").attr("aria-hidden", "false");
        }

        function closeKnowledgePopup() {
            $knowledgePopup.removeClass("is-open").attr("aria-hidden", "true");
        }

        function uploadKnowledgeFile() {
            // 팝업이 열린 사이 선택 카테고리가 풀렸는지 한 번 더 방어
            if (!selectedCategoryId) {
                showKnowledgeResult("카테고리가 선택되지 않았습니다. 왼쪽 목록에서 선택하세요.", "error");
                return;
            }

            let fileInput = document.getElementById("knowledgeFile");
            let file = fileInput.files && fileInput.files[0];
            if (!file) {
                showKnowledgeResult("업로드할 텍스트 파일을 선택해 주세요.", "error");
                return;
            }

            let formData = new FormData();
            // 필드명은 백엔드 @RequestParam 과 정확히 일치해야 한다.
            formData.append("file", file);
            formData.append("categoryId", selectedCategoryId);

            let $btn = $("#btnKnowledgePopupUpload");
            $btn.prop("disabled", true);
            showKnowledgeResult("업로드 중...", "loading");

            // FormData 사용 시 contentType/processData 를 끈다(브라우저가 multipart boundary 자동 설정)
            $.ajax({
                url:         uploadUrl,
                type:        "POST",
                data:        formData,
                processData: false,
                contentType: false,
                dataType:    "json"
            }).done(function (data) {
                if (data && data.success) {
                    closeKnowledgePopup();
                    reloadKnowledgeGrid();
                } else {
                    showKnowledgeResult((data && data.message) ? data.message : "업로드에 실패했습니다.", "error");
                }
            })
            .fail(function (xhr, status, error) {
                showKnowledgeResult("업로드 중 오류가 발생했습니다. (" + status + " : " + error + ")", "error");
            })
            .always(function () {
                $btn.prop("disabled", false);
            });
        }

        $("#btnKnowledgeCreate").on("click", openKnowledgePopup);
        $("#btnKnowledgePopupCancel").on("click", closeKnowledgePopup);
        $("#btnKnowledgePopupClose").on("click", closeKnowledgePopup);
        $("#knowledgePopupDim").on("click", closeKnowledgePopup);
        $("#btnKnowledgePopupUpload").on("click", uploadKnowledgeFile);

        /* ══════════════════════════════════════════════════════════
           청크 목록(상세) 레이어 팝업
           - 조회: GET /user/knowledge/knowledges/chunkings?parent_document_id={fileId}
                   → 200 JSON 배열 [{ chunkId, parentDocumentId, categoryId,
                                       chunkIndex, totalChunks, content }, ...] (chunk_index ASC)
           - 닫기 버튼만 존재
           ══════════════════════════════════════════════════════════ */
        let $chunkPopup = $("#chunkPopup");

        function closeChunkPopup() {
            $chunkPopup.removeClass("is-open").attr("aria-hidden", "true");
        }

        // 청크 목록 렌더링 — content/순번 모두 textContent 로만 삽입(XSS 방지)
        function renderChunkList(list) {
            let tbody = document.getElementById("chunkListBody");
            tbody.textContent = "";   // 초기화

            if (!list || list.length === 0) {
                let trEmpty = document.createElement("tr");
                let tdEmpty = document.createElement("td");
                tdEmpty.colSpan = 2;
                tdEmpty.className = "chunk-list__empty";
                tdEmpty.textContent = "조회된 청크가 없습니다.";
                trEmpty.appendChild(tdEmpty);
                tbody.appendChild(trEmpty);
                return;
            }

            list.forEach(function (chunk) {
                let tr = document.createElement("tr");

                let tdIdx = document.createElement("td");
                tdIdx.className = "chunk-list__idx";
                tdIdx.textContent = (chunk.chunkIndex == null) ? "" : String(chunk.chunkIndex);

                let tdContent = document.createElement("td");
                tdContent.className = "chunk-list__content";
                tdContent.textContent = (chunk.content == null) ? "" : chunk.content;

                tr.appendChild(tdIdx);
                tr.appendChild(tdContent);
                tbody.appendChild(tr);
            });
        }

        function openChunkPopup(fileId) {
            if (!fileId) {
                alert("지식파일 정보를 확인할 수 없습니다.");
                return;
            }

            // 팝업을 먼저 열고 로딩 표시
            document.getElementById("chunkPopupMeta").textContent = "청킹 목록을 불러오는 중...";
            renderChunkList([]);
            $chunkPopup.addClass("is-open").attr("aria-hidden", "false");

            $.ajax({
                // parent_document_id 는 경로 변수(/knowledges/chunks/{parent_document_id})로 전달
                url:      chunkListUrl + "/" + encodeURIComponent(fileId),
                type:     "GET",
                dataType: "json"
            }).done(function (list) {
                let count = (list && list.length) ? list.length : 0;
                document.getElementById("chunkPopupMeta").textContent =
                    "총 " + count + "개 청크";
                renderChunkList(list);
            })
            .fail(function (xhr, status, error) {
                document.getElementById("chunkPopupMeta").textContent =
                    "청킹 목록 조회 중 오류가 발생했습니다. (" + status + " : " + error + ")";
                renderChunkList([]);
            });
        }

        // 그리드 내 "상세" 버튼 위임 클릭 — 행 선택(onSelectRow)으로의 전파 차단
        $("#knowledgeGrid").on("click", ".btn-detail", function (e) {
            e.stopPropagation();
            openChunkPopup($(this).data("id"));
        });

        $("#btnChunkPopupClose").on("click", closeChunkPopup);
        $("#btnChunkPopupCloseX").on("click", closeChunkPopup);
        $("#chunkPopupDim").on("click", closeChunkPopup);
    });
</script>

</body>
</html>