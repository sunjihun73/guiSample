/*
 * 공통 알림/확인 대화상자.
 *
 * 브라우저 기본 alert()/confirm() 을 대신한다. 마크업은 WEB-INF/views/common/dialog.jsp,
 * 스타일은 common.css 의 .layer-popup--message 다.
 *
 *   appDialog.alert('저장했습니다.');
 *   appDialog.warn('태스크 이름을 입력하세요.');            // 경고 톤
 *   appDialog.confirm('완료하시겠습니까?', {
 *       title: '단계 완료', confirmText: '완료',
 *       onConfirm: function () { ... }, onCancel: function () { ... }
 *   });
 *
 * 기본 대화상자와 달리 스크립트를 멈추지 않는다 — 결과는 콜백으로 받는다.
 * 조각을 끼우지 않은 화면에서 호출하면 기본 alert()/confirm() 으로 떨어진다(조용히 사라지지 않게).
 */
(function (global) {
    'use strict';

    let els    = null;    // 캐시된 DOM
    let active = null;    // 열려 있는 대화상자의 콜백 { onConfirm, onCancel }
    let lastFocused = null;

    function elements() {
        if (els) return els;

        let root = document.getElementById('appDialog');
        if (!root) return null;

        els = {
            root:    root,
            dim:     document.getElementById('appDialogDim'),
            title:   document.getElementById('appDialogTitle'),
            message: document.getElementById('appDialogMessage'),
            confirm: document.getElementById('appDialogConfirm'),
            cancel:  document.getElementById('appDialogCancel'),
            close:   document.getElementById('appDialogClose')
        };

        els.confirm.addEventListener('click', function () { finish(true); });
        els.cancel.addEventListener('click',  function () { finish(false); });
        els.close.addEventListener('click',   function () { finish(false); });
        els.dim.addEventListener('click',     function () { finish(false); });

        /* ESC 는 취소. 기본 confirm 의 동작과 맞춘다. */
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && els.root.classList.contains('is-open')) {
                e.preventDefault();
                finish(false);
            }
        });

        return els;
    }

    function open(options) {
        let e = elements();

        /* 조각이 없는 화면 — 기본 대화상자로 떨어진다. */
        if (!e) {
            if (options.mode === 'confirm') {
                if (global.confirm(options.message)) {
                    if (options.onConfirm) options.onConfirm();
                } else if (options.onCancel) {
                    options.onCancel();
                }
            } else {
                global.alert(options.message);
                if (options.onConfirm) options.onConfirm();
            }
            return;
        }

        /* 이미 열려 있으면 앞의 것은 취소로 정리하고 새 내용으로 바꾼다. */
        if (active) finish(false);

        active = { onConfirm: options.onConfirm, onCancel: options.onCancel };

        e.title.textContent   = options.title || (options.mode === 'confirm' ? '확인' : '알림');
        /* 사용자 입력·서버 메시지가 그대로 들어오므로 textContent 로 넣는다(HTML 해석 금지). */
        e.message.textContent = options.message || '';

        e.confirm.textContent = options.confirmText || '확인';
        e.cancel.textContent  = options.cancelText || '취소';
        e.cancel.style.display = (options.mode === 'confirm') ? '' : 'none';

        e.root.classList.remove('is-info', 'is-warn');
        e.root.classList.add(options.tone === 'warn' ? 'is-warn' : 'is-info');

        lastFocused = document.activeElement;
        e.root.classList.add('is-open');
        e.root.setAttribute('aria-hidden', 'false');
        e.confirm.focus();
    }

    /** 닫고 콜백을 부른다. confirmed = 확인 버튼을 눌렀는지. */
    function finish(confirmed) {
        let e = elements();
        if (!e || !active) return;

        let callbacks = active;
        active = null;

        e.root.classList.remove('is-open');
        e.root.setAttribute('aria-hidden', 'true');

        /* 열기 전 초점으로 되돌린다(키보드 사용자가 맥락을 잃지 않도록). */
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
        lastFocused = null;

        if (confirmed) {
            if (callbacks.onConfirm) callbacks.onConfirm();
        } else if (callbacks.onCancel) {
            callbacks.onCancel();
        }
    }

    global.appDialog = {
        /** 알림 — 확인 버튼 하나. */
        alert: function (message, options) {
            options = options || {};
            open({
                mode:        'alert',
                message:     message,
                title:       options.title,
                tone:        options.tone,
                confirmText: options.confirmText,
                onConfirm:   options.onClose
            });
        },

        /** 경고 알림 — 입력 오류·실패 안내용(아이콘과 색이 달라진다). */
        warn: function (message, options) {
            options = options || {};
            options.tone = 'warn';
            options.title = options.title || '경고';
            this.alert(message, options);
        },

        /** 확인 — 확인/취소 두 버튼. 결과는 onConfirm / onCancel 로 받는다. */
        confirm: function (message, options) {
            options = options || {};
            open({
                mode:        'confirm',
                message:     message,
                title:       options.title,
                tone:        options.tone,
                confirmText: options.confirmText,
                cancelText:  options.cancelText,
                onConfirm:   options.onConfirm,
                onCancel:    options.onCancel
            });
        },

        /** 열려 있는 대화상자를 취소로 닫는다. */
        close: function () {
            finish(false);
        }
    };
})(window);
