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
    <title>RAG — 기술테스트 시스템</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/common.css">

    <%-- CSRF 토큰 — /js/csrf.js 가 읽어 모든 비-GET ajax 요청 헤더에 부착하고,
         fetch 는 window.csrfHeader() 로 직접 사용한다. --%>
    <meta name="_csrf" content="${_csrf.token}">
    <meta name="_csrf_header" content="${_csrf.headerName}">
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
    <% String activeMenu = "rag"; %>
    <%@ include file="/WEB-INF/views/common/sidebar.jsp" %>

    <!-- ── Main Content ── -->
    <main class="main-content rag-chat">

        <header class="page-header">
            <p class="page-header__eyebrow">Assistant</p>
            <h1 class="page-header__title">RAG 챗봇</h1>
            <p class="page-header__desc">색인된 지식베이스를 근거로 답변합니다. 카테고리를 선택해 검색 범위를 좁힐 수 있습니다.</p>
        </header>

        <!-- 본문: 카테고리 패널 + 세션 패널 + 우측 채팅 UI (3단) -->
        <div class="rag-body">

            <!-- 카테고리 선택 패널 (최좌측, 약 1/5) -->
            <aside class="rag-cat-panel" aria-label="카테고리 선택">
                <p class="rag-cat-panel__title">카테고리</p>
                <div id="ragCatList" class="rag-cat-list" role="group" aria-label="카테고리 목록">
                    <button type="button" class="rag-cat-btn is-selected"
                            data-category-id="" aria-pressed="true">전체</button>
                </div>
                <p id="ragCatMsg" class="rag-cat-msg"></p>
            </aside>

            <!-- 채팅 세션 패널 (카테고리 다음, 카테고리보다 좁게) -->
            <aside class="rag-session-panel" aria-label="채팅 세션">
                <div class="rag-session-panel__header">
                    <p class="rag-session-panel__title">채팅세션</p>
                    <button type="button" id="ragSessionNewBtn" class="rag-session-newbtn" title="새 채팅 시작">
                        <svg viewBox="0 0 16 16" width="12" height="12" fill="none" aria-hidden="true">
                            <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>
                        </svg>
                        새 채팅
                    </button>
                </div>
                <div id="ragSessionList" class="rag-session-list" role="group" aria-label="세션 목록"></div>
                <p id="ragSessionMsg" class="rag-session-msg"></p>
            </aside>

            <!-- 채팅 UI -->
            <div class="chat-window">
            <!-- 메시지 표시 영역 (위쪽) -->
            <div id="chatMessages" class="chat-messages" aria-live="polite">
                <div class="chat-msg chat-msg--bot">
                    <div class="chat-bubble">안녕하세요. 무엇을 도와드릴까요? 궁금한 내용을 입력해 주세요.</div>
                </div>
            </div>

            <!-- 입력 바 (아래쪽) -->
            <form id="chatForm" class="chat-input-bar" onsubmit="return false;">
                <textarea id="chatInput" class="chat-input" rows="1" aria-label="메시지 입력"
                          placeholder="메시지를 입력하세요  (Enter 전송 · Shift+Enter 줄바꿈)"></textarea>
                <button type="submit" id="chatSendBtn" class="chat-send-btn">
                    전송
                    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
                        <path d="M2.5 8h10M8.5 4l4 4-4 4" stroke="currentColor" stroke-width="1.6"
                              stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </button>
            </form>
            </div>

        </div>

    </main>
</div>

<!-- jQuery -->
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="${pageContext.request.contextPath}/js/csrf.js"></script>

<script>
    /* ── 사이드바 토글 (다른 페이지와 동일) ───────────────── */
    $(function () {
        let $sidebar = $('#sidebar');
        let STORAGE_KEY = 'sidebar_collapsed';

        if (sessionStorage.getItem(STORAGE_KEY) === 'true') {
            $sidebar.addClass('collapsed');
        }

        $('#toggleBtn').on('click', function () {
            let isCollapsed = $sidebar.toggleClass('collapsed').hasClass('collapsed');
            sessionStorage.setItem(STORAGE_KEY, isCollapsed);
        });
    });

    /* ── 카테고리 목록 로딩 + 단일 선택 ───────────────────── */
    $(function () {
        /* 백엔드 응답: GET /user/category/categories?page=1&rows=1000
           →  200  { page, total, records, rows: [{ categoryId, categoryName, … }] } */
        let CATEGORIES_URL = '${pageContext.request.contextPath}/user/category/categories?page=1&rows=1000';

        let $list = $('#ragCatList');
        let $msg  = $('#ragCatMsg');

        /* 클릭한 버튼만 선택 표시 (단일 선택) */
        $list.on('click', '.rag-cat-btn', function () {
            $list.find('.rag-cat-btn').removeClass('is-selected').attr('aria-pressed', 'false');
            $(this).addClass('is-selected').attr('aria-pressed', 'true');
        });

        /* 카테고리 목록 로딩 — 실패해도 "전체" 검색은 계속 동작 */
        $.ajax({
            url:      CATEGORIES_URL,
            type:     'GET',
            dataType: 'json',
            headers:  { 'Accept': 'application/json' }
        }).done(function (data) {
            let rows = data && Array.isArray(data.rows) ? data.rows : [];

            $.each(rows, function (i, cat) {
                if (!cat || !cat.categoryId) return;   /* continue */

                /* text 옵션으로 넣으면 jQuery가 이스케이프 → XSS 방지 */
                let $btn = $('<button>', {
                    type:           'button',
                    'class':        'rag-cat-btn',
                    'aria-pressed': 'false',
                    text:           cat.categoryName || '(이름 없음)',
                    title:          cat.categoryName || ''
                }).attr('data-category-id', cat.categoryId);
                $list.append($btn);
            });

            if (!rows.length) {
                $msg.text('등록된 카테고리가 없습니다.');
            }
        }).fail(function (xhr) {
            $msg.text('카테고리 목록을 불러오지 못했습니다. (HTTP ' + xhr.status + ')')
                .addClass('is-error');
        });
    });

    /* ── RAG 채팅 세션 + 답변 스트리밍 ───────────────────────
       세션 목록/생성/복원과 스트리밍은 currentSessionId 상태를 공유하므로
       하나의 초기화 블록으로 통합한다. (스트리밍만 fetch 유지 — 아래 주석 참고) */
    $(function () {
        let CONTEXT      = '${pageContext.request.contextPath}';
        /* D: POST /user/rag/docs  body { query, categoryId?, sessionId? }
           →  200  text/event-stream  (data: 토큰 …)  답변 토큰 스트림 */
        let DOCS_URL     = CONTEXT + '/user/rag/docs';
        /* A: GET  /user/rag/sessions?page=1&rows=100  →  { page,total,records,rows:[{sessionId,title,createDate}] }
           B: POST /user/rag/sessions  body {title?}   →  { success,sessionId,title,message }
           C: GET  /user/rag/sessions/{id}/messages    →  { sessionId, rows:[{role,content,model,promptTokens,completionTokens,createDate}] } */
        let SESSIONS_URL = CONTEXT + '/user/rag/sessions';

        let GREETING = '안녕하세요. 무엇을 도와드릴까요? 궁금한 내용을 입력해 주세요.';
        let DEFAULT_TITLE = '새로운채팅';   /* 서버 기본 세션 제목 — 자동 요약 변경 트리거 판별용 */

        let $messages    = $('#chatMessages');
        let $form        = $('#chatForm');
        let $input       = $('#chatInput');
        let $sendBtn     = $('#chatSendBtn');

        let $sessionList = $('#ragSessionList');
        let $sessionMsg  = $('#ragSessionMsg');
        let $newBtn      = $('#ragSessionNewBtn');

        /* 현재 활성 세션 ID (없으면 null). 요구4에서 전송 시 자동 생성. */
        let currentSessionId = null;

        /* $.ajax(jqXHR)를 표준 Promise로 감싸 실패 시 Error(message) 로 정규화한다.
           → async/await 의 catch(e) 에서 e.message 를 일관되게 사용하기 위함. */
        function ajaxJson(options) {
            return Promise.resolve($.ajax(options)).catch(function (jqXHR) {
                let msg = (jqXHR && jqXHR.status)
                        ? ('HTTP ' + jqXHR.status)
                        : ((jqXHR && jqXHR.statusText) || '요청 실패');
                throw new Error(msg);
            });
        }

        /* 메시지 말풍선 추가 — 텍스트는 text()로 삽입(XSS 방지).
           말풍선(.chat-bubble) jQuery 객체를 반환해 스트리밍 토큰을 이어붙일 수 있게 한다. */
        function appendMessage(role, text) {
            let $bubble = $('<div>', { 'class': 'chat-bubble', text: text });
            let $row = $('<div>', { 'class': 'chat-msg chat-msg--' + role }).append($bubble);

            $messages.append($row);
            scrollToBottom();
            return $bubble;
        }

        /* 타이핑 인디케이터 표시/제거 */
        function showTyping() {
            let $typing = $('<div>', { 'class': 'chat-typing' })
                .append($('<span>'), $('<span>'), $('<span>'));
            let $bubble = $('<div>', { 'class': 'chat-bubble' }).append($typing);
            let $row = $('<div>', { 'class': 'chat-msg chat-msg--bot', id: 'chatTyping' }).append($bubble);

            $messages.append($row);
            scrollToBottom();
        }
        function removeTyping() {
            $('#chatTyping').remove();
        }

        function scrollToBottom() {
            $messages.scrollTop($messages[0].scrollHeight);
        }

        /* textarea 높이 자동 조절 */
        function autoGrow() {
            $input.css('height', 'auto')
                  .css('height', Math.min($input[0].scrollHeight, 140) + 'px');
        }

        /* 좌측 패널에서 선택된 카테고리 ID 반환 ("전체"면 빈 문자열) */
        function getSelectedCategoryId() {
            let $sel = $('#ragCatList .rag-cat-btn.is-selected');
            return $sel.length ? ($sel.attr('data-category-id') || '') : '';
        }

        /* ── 채팅창 초기화 (기본 인사 1건만 남김) ── */
        function resetChatWindow() {
            $messages.empty();
            appendMessage('bot', GREETING);
            scrollToBottom();
        }

        /* createDate 보기 좋게 — "2026-07-08 10:11:12.0" → "2026-07-08 10:11:12" (소수부 절삭) */
        function formatDate(s) {
            if (!s) return '';
            let t = String(s);
            let dot = t.indexOf('.');
            return dot > -1 ? t.slice(0, dot) : t;
        }

        /* ── 세션 항목 렌더 (prepend=true 시 목록 최상단에 추가) ── */
        function renderSessionItem(session, prepend) {
            let $title = $('<span>', {
                'class': 'rag-session-item__title',
                text:    session.title || '새로운채팅'   /* XSS 방지 */
            });
            let $date = $('<span>', {
                'class': 'rag-session-item__date',
                text:    formatDate(session.createDate)
            });
            let $item = $('<button>', { type: 'button', 'class': 'rag-session-item' })
                .attr('data-session-id', session.sessionId)
                .append($title, $date);

            if (prepend) {
                $sessionList.prepend($item);
            } else {
                $sessionList.append($item);
            }
            return $item;
        }

        /* 활성 세션 설정 + 목록 선택 표시 이동 */
        function setActiveSession(sessionId) {
            currentSessionId = sessionId;
            $sessionList.find('.rag-session-item').each(function () {
                $(this).toggleClass('is-selected', $(this).attr('data-session-id') === sessionId);
            });
        }

        /* A: 세션 목록 로딩 (페이지 로드 시). 목록 있으면 최신(최상단) 세션 자동 활성 + 대화 복원. */
        async function loadSessions() {
            try {
                let data = await ajaxJson({
                    url:      SESSIONS_URL + '?page=1&rows=100',
                    type:     'GET',
                    dataType: 'json',
                    headers:  { 'Accept': 'application/json' }
                });
                let rows = data && Array.isArray(data.rows) ? data.rows : [];

                $sessionList.empty();
                $.each(rows, function (i, s) {
                    if (s && s.sessionId) renderSessionItem(s, false);
                });

                if (!rows.length) {
                    $sessionMsg.text('채팅을 시작하면 세션이 생성됩니다.').removeClass('is-error');
                    currentSessionId = null;
                } else {
                    $sessionMsg.text('').removeClass('is-error');
                    /* 최신(최상단, create_date DESC) 세션 자동 활성화 + 복원 */
                    setActiveSession(rows[0].sessionId);
                    await loadMessages(rows[0].sessionId);
                }
            } catch (e) {
                $sessionMsg.text('세션 목록을 불러오지 못했습니다. (' + e.message + ')')
                           .addClass('is-error');
            }
        }

        /* B: 새 세션 생성 → 목록 최상단 추가 + 활성화. 생성된 sessionId 반환. */
        async function createSession() {
            let data = await ajaxJson({
                url:         SESSIONS_URL,
                type:        'POST',
                contentType: 'application/json',
                dataType:    'json',
                headers:     { 'Accept': 'application/json' },
                data:        JSON.stringify({})
            });
            if (!data || !data.success || !data.sessionId) {
                throw new Error((data && data.message) || '세션 생성에 실패했습니다.');
            }

            $sessionMsg.text('').removeClass('is-error');

            renderSessionItem({
                sessionId:  data.sessionId,
                title:      data.title || '새로운채팅',
                createDate: ''
            }, true);
            setActiveSession(data.sessionId);
            return data.sessionId;
        }

        /* C: 세션 대화 복원 → 채팅창을 해당 대화로 교체. assistant → bot 매핑. */
        async function loadMessages(sessionId) {
            try {
                let data = await ajaxJson({
                    url:      SESSIONS_URL + '/' + encodeURIComponent(sessionId) + '/messages',
                    type:     'GET',
                    dataType: 'json',
                    headers:  { 'Accept': 'application/json' }
                });
                let rows = data && Array.isArray(data.rows) ? data.rows : [];

                $messages.empty();
                if (!rows.length) {
                    appendMessage('bot', GREETING);
                    scrollToBottom();
                    return;
                }
                $.each(rows, function (i, m) {
                    let cls = (m.role === 'assistant') ? 'bot' : 'user';   /* 역할 매핑 */
                    appendMessage(cls, m.content || '');
                });
                scrollToBottom();
            } catch (e) {
                $messages.empty();
                appendMessage('bot', '이전 대화를 불러오지 못했습니다. (' + e.message + ')');
            }
        }

        /* sessionId 로 목록 항목 jQuery 객체 찾기(속성 선택자 대신 순회 — 값 안전). 없으면 null. */
        function getSessionItem(sessionId) {
            let $found = null;
            $sessionList.find('.rag-session-item').each(function () {
                if ($(this).attr('data-session-id') === sessionId) {
                    $found = $(this);
                    return false;   /* break */
                }
            });
            return $found;
        }

        /* A 재조회로 특정 세션의 최신 title 조회. 없으면 null. */
        async function fetchSessionTitle(sessionId) {
            let data = await ajaxJson({
                url:      SESSIONS_URL + '?page=1&rows=100',
                type:     'GET',
                dataType: 'json',
                headers:  { 'Accept': 'application/json' }
            });
            let rows = data && Array.isArray(data.rows) ? data.rows : [];
            for (let i = 0; i < rows.length; i++) {
                if (rows[i] && rows[i].sessionId === sessionId) return rows[i].title || '';
            }
            return null;
        }

        /* T7: /docs 스트림 정상 종료 후 세션 제목 자동 요약 반영.
           - 서버가 비동기로 "새로운채팅"→요약 제목 갱신(수백 ms~수 초 지연). GET /sessions 재조회로만 확인 가능.
           - 항목 제목이 아직 기본값일 때만 재조회(그 외엔 재조회 없음). 목록 항목 title 텍스트만 갱신 —
             활성 선택 상태/채팅창은 건드리지 않는다. 지연 재조회 2회(1초 뒤, 여전히 기본값이면 3초 뒤). */
        function refreshSessionTitle(sessionId) {
            if (!sessionId) return;
            let $item = getSessionItem(sessionId);
            let $titleEl = $item ? $item.find('.rag-session-item__title') : null;
            if (!$titleEl || !$titleEl.length || $titleEl.text() !== DEFAULT_TITLE) return;   /* 기본값 아니면 조회 불필요 */

            let delays = [1000, 3000];
            let idx = 0;

            function attempt() {
                setTimeout(async function () {
                    /* 재조회 직전에도 여전히 기본값일 때만 진행(중간에 반영/삭제됐으면 종료) */
                    let $curItem = getSessionItem(sessionId);
                    let $curTitleEl = $curItem ? $curItem.find('.rag-session-item__title') : null;
                    if (!$curTitleEl || !$curTitleEl.length || $curTitleEl.text() !== DEFAULT_TITLE) return;
                    try {
                        let title = await fetchSessionTitle(sessionId);
                        if (title && title !== DEFAULT_TITLE) {
                            $curTitleEl.text(title);   /* 항목 텍스트만 갱신(XSS 방지: text()) */
                            return;                     /* 반영 완료 → 재시도 중단 */
                        }
                    } catch (e) {
                        /* 재조회 실패는 조용히 무시 — 제목은 기본값 유지, 채팅 흐름 영향 없음 */
                    }
                    idx++;
                    if (idx < delays.length) attempt();       /* 아직 기본값이면 다음 지연으로 재시도 */
                }, delays[idx]);
            }
            attempt();
        }

        /* /user/rag/docs 답변 스트림 소비.
           jQuery $.ajax 는 응답을 끝까지 받은 뒤에야 콜백을 호출하므로 토큰 스트리밍이 불가능하다.
           따라서 이 함수만 fetch ReadableStream 을 직접 읽어 SSE(data: 라인)를 파싱하고
           토큰마다 onToken(text)을 호출한다. (EventSource는 GET만 지원하므로 미사용)
           Spring SSE는 'data:' 뒤에 공백을 추가하지 않으므로 slice(5)로 원문을 보존한다. */
        async function streamAnswer(query, onToken) {
            /* 카테고리 선택 시 categoryId를 함께 전송 — 서버가 metadata 필터로 사용.
               "전체"(빈 값)면 필드를 생략하여 전체 벡터 검색. */
            let payload = { query: query };
            let categoryId = getSelectedCategoryId();
            if (categoryId) payload.categoryId = categoryId;
            /* 활성 세션 ID 전송 — 서버가 대화 저장에 사용 (요구5) */
            if (currentSessionId) payload.sessionId = currentSessionId;

            /* CSRF 토큰 헤더를 함께 실어야 Spring Security 를 통과한다 (/js/csrf.js). */
            let headers = Object.assign({
                'Content-Type': 'application/json',
                'Accept':       'text/event-stream'
            }, window.csrfHeader());

            let res = await fetch(DOCS_URL, {
                method:  'POST',
                headers: headers,
                body:    JSON.stringify(payload)
            });
            /* 세션 만료(401) 또는 CSRF 토큰 무효(403) → 새로고침해 Keycloak 로그인 흐름으로 되돌린다. */
            if (res.status === 401 || res.status === 403) { window.location.reload(); return; }
            if (!res.ok)   throw new Error('HTTP ' + res.status);
            if (!res.body) throw new Error('스트림을 지원하지 않는 브라우저입니다.');

            let reader  = res.body.getReader();
            let decoder = new TextDecoder('utf-8');
            let buffer  = '';

            while (true) {
                let chunk = await reader.read();
                if (chunk.done) break;

                buffer += decoder.decode(chunk.value, { stream: true });

                /* 이벤트 경계는 빈 줄(\n\n). 마지막 미완성 조각은 버퍼에 남긴다. */
                let events = buffer.split('\n\n');
                buffer = events.pop();

                $.each(events, function (i, evt) {
                    let dataLines = [];
                    $.each(evt.split('\n'), function (j, line) {
                        if (line.indexOf('data:') === 0) {
                            dataLines.push(line.slice(5));
                        }
                    });
                    if (dataLines.length) onToken(dataLines.join('\n'));
                });
            }
        }

        /* 전송 재진입 가드 — 같은 메시지가 두 번 나가는 것을 마지막 단계에서 한 번 더 막는다. */
        let sending = false;
        async function handleSend() {
            if (sending) return;
            sending = true;
            try {
                await doSend();
            } finally {
                sending = false;
            }
        }

        async function doSend() {
            let text = $.trim($input.val());
            if (!text) return;

            /* 요구4: 활성 세션이 없으면 먼저 "새로운채팅" 세션을 자동 생성해 귀속.
               (채팅창은 초기화하지 않고 현재 메시지를 그대로 이어간다) */
            if (!currentSessionId) {
                $sendBtn.prop('disabled', true);
                try {
                    await createSession();
                } catch (e) {
                    appendMessage('bot', '세션 생성 중 오류가 발생했습니다. (' + e.message + ')');
                    $sendBtn.prop('disabled', false);
                    return;
                }
                $sendBtn.prop('disabled', false);
            }

            /* 이 메시지가 귀속된 세션 — 스트리밍 중 세션 전환에도 안전하게 캡처 */
            let sendSessionId = currentSessionId;

            appendMessage('user', text);
            $input.val('');
            autoGrow();
            $input.trigger('focus');

            $sendBtn.prop('disabled', true);
            showTyping();

            let $bubble = null;
            try {
                await streamAnswer(text, function (token) {
                    if (!$bubble) {
                        removeTyping();
                        $bubble = appendMessage('bot', '');
                        $bubble.addClass('is-streaming');
                    }
                    $bubble.text($bubble.text() + token);
                    scrollToBottom();
                });

                if (!$bubble) {
                    removeTyping();
                    appendMessage('bot', '응답을 받지 못했습니다.');
                } else {
                    $bubble.removeClass('is-streaming');
                    /* T7: 정상 답변 완료 → 서버의 세션 제목 자동 요약 반영 시도(기본값일 때만) */
                    refreshSessionTitle(sendSessionId);
                }
            } catch (e) {
                removeTyping();
                if ($bubble) {
                    $bubble.removeClass('is-streaming');
                    $bubble.text($bubble.text() + '\n[오류] ' + e.message);
                } else {
                    appendMessage('bot', '답변 생성 중 오류가 발생했습니다. (' + e.message + ')');
                }
            } finally {
                $sendBtn.prop('disabled', false);
            }
        }

        $form.on('submit', function (e) {
            e.preventDefault();
            handleSend();
        });
        $input.on('input', autoGrow);

        /* IME(한글) 조합 상태 추적.
           macOS Chrome 은 조합 중 Enter 로 keydown 을 두 번 보낸다.
             1) 조합 확정용 Enter (isComposing=true) → 여기서 전송하면 입력창을 비운 직후
                compositionend 가 확정 문자를 다시 써넣어 텍스트가 되살아난다
             2) 전송용 Enter (isComposing=false) → 되살아난 텍스트를 또 전송 = 중복 전송
           따라서 1) 은 반드시 무시해야 한다. */
        let isComposing = false;
        $input.on('compositionstart', function () { isComposing = true;  });
        $input.on('compositionend',   function () { isComposing = false; });

        /* Enter 전송, Shift+Enter 줄바꿈 */
        $input.on('keydown', function (e) {
            if (e.key !== 'Enter' || e.shiftKey) return;

            /* jQuery 이벤트 객체는 isComposing 을 복사하지 않으므로 originalEvent 에서 읽는다.
               keyCode 229 는 IME 처리 중임을 뜻하는 레거시 신호(Windows Chrome 등). */
            let oe = e.originalEvent;
            if (isComposing || (oe && oe.isComposing) || e.which === 229) {
                return; /* 조합 확정용 Enter — 전송하지 않는다 */
            }

            e.preventDefault();
            handleSend();
        });

        /* 요구3: "새 채팅" → 새 세션 생성 + 채팅창 초기화 + 목록 상단 추가·활성화 */
        $newBtn.on('click', async function () {
            $newBtn.prop('disabled', true);
            try {
                await createSession();
                resetChatWindow();
                $input.trigger('focus');
            } catch (e) {
                $sessionMsg.text('새 채팅 생성 실패: ' + e.message).addClass('is-error');
            } finally {
                $newBtn.prop('disabled', false);
            }
        });

        /* 요구6: 세션 항목 클릭 → 활성 전환 + 대화 복원 */
        $sessionList.on('click', '.rag-session-item', async function () {
            let sid = $(this).attr('data-session-id');
            if (!sid || sid === currentSessionId) return;
            setActiveSession(sid);
            await loadMessages(sid);
        });

        $input.trigger('focus');

        /* 요구7: 페이지 로드 시 세션 목록 초기 로드 */
        loadSessions();
    });
</script>

</body>
</html>