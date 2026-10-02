<%@ page pageEncoding="UTF-8" %>
<%--
    공통 알림/확인 레이어 팝업.

    브라우저 기본 alert()/confirm() 대신 쓰는 화면 내 대화상자다.
    마크업은 여기 한 곳에만 두고, 동작은 static/js/dialog.js 가 맡는다.

    사용법 — 화면에 이 조각을 끼우고 dialog.js 를 읽은 뒤:

        appDialog.alert('저장했습니다.');
        appDialog.warn('태스크 이름을 입력하세요.');
        appDialog.confirm('완료하시겠습니까?', { onConfirm: function () { ... } });

    <%@ include %>(정적 include)로 끼운다 — 런타임 <jsp:include> 는 DispatcherType.INCLUDE 를
    일으키는데 SecurityConfig 는 FORWARD/ERROR 만 열어 두었다(sidebar.jsp 와 같은 이유).
--%>
<div class="layer-popup layer-popup--message is-info" id="appDialog" aria-hidden="true">
    <div class="layer-popup__dim" id="appDialogDim"></div>
    <div class="layer-popup__panel" role="alertdialog" aria-modal="true"
         aria-labelledby="appDialogTitle" aria-describedby="appDialogMessage">

        <div class="layer-popup__header">
            <div class="dialog-head">
                <span class="dialog-icon dialog-icon--info" aria-hidden="true">
                    <svg viewBox="0 0 20 20" fill="none">
                        <circle cx="10" cy="10" r="8" stroke="currentColor" stroke-width="1.5"/>
                        <path d="M10 9v4.5M10 6.6v.9" stroke="currentColor" stroke-width="1.8"
                              stroke-linecap="round"/>
                    </svg>
                </span>
                <span class="dialog-icon dialog-icon--warn" aria-hidden="true">
                    <svg viewBox="0 0 20 20" fill="none">
                        <path d="M10 2.8 18 16.5H2L10 2.8Z" stroke="currentColor" stroke-width="1.5"
                              stroke-linejoin="round"/>
                        <path d="M10 8v3.4M10 13.6v.9" stroke="currentColor" stroke-width="1.8"
                              stroke-linecap="round"/>
                    </svg>
                </span>
                <h2 class="layer-popup__title" id="appDialogTitle">알림</h2>
            </div>

            <button type="button" class="layer-popup__close" id="appDialogClose" aria-label="닫기">
                <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
            </button>
        </div>

        <div class="layer-popup__body">
            <%-- 메시지는 dialog.js 가 textContent 로 넣는다(사용자 입력이 섞여도 안전하도록). --%>
            <p class="layer-popup__message" id="appDialogMessage"></p>
        </div>

        <div class="layer-popup__footer">
            <button type="button" id="appDialogCancel" class="search-form__btn search-form__btn--ghost">취소</button>
            <button type="button" id="appDialogConfirm" class="search-form__btn">확인</button>
        </div>
    </div>
</div>
