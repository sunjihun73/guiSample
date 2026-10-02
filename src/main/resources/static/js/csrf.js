/*
 * CSRF 토큰 전역 배선.
 *
 * Spring Security 의 CSRF 보호가 켜져 있으므로 모든 비-GET 요청은 토큰 헤더를 실어야 한다.
 * 각 JSP <head> 의 <meta name="_csrf"> / <meta name="_csrf_header"> 를 읽어
 *   1) jQuery 의 모든 ajax 요청에 자동 부착 ($.ajaxSend — 개별 호출의 beforeSend 와 충돌하지 않음)
 *   2) fetch 용 헤더 객체를 window.csrfHeader() 로 노출
 * 한다.
 *
 * 세션 만료(60분) 시 서버 응답은
 *   - GET 등 조회      → 401 (SecurityConfig 의 AJAX 전용 entry point)
 *   - POST 등 변경 요청 → 403 (세션이 사라져 CSRF 토큰이 무효)
 * 이므로 두 경우 모두 전역 핸들러가 페이지를 새로고침해 정상 Keycloak 로그인 흐름을 타게 한다.
 *
 * jQuery 보다 뒤에 로드할 것.
 */
(function () {
    'use strict';

    function metaContent(name) {
        let el = document.querySelector('meta[name="' + name + '"]');
        return el ? el.getAttribute('content') : null;
    }

    let token  = metaContent('_csrf');
    let header = metaContent('_csrf_header');

    /* fetch 용 — let headers = Object.assign({...}, window.csrfHeader()); */
    window.csrfHeader = function () {
        let headers = {};
        if (token && header) headers[header] = token;
        return headers;
    };

    function needsToken(method) {
        let m = (method || 'GET').toUpperCase();
        return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS' && m !== 'TRACE';
    }

    if (!window.jQuery) return;

    jQuery(document).ajaxSend(function (event, xhr, settings) {
        if (token && header && needsToken(settings.type)) {
            xhr.setRequestHeader(header, token);
        }
    });

    jQuery(document).ajaxError(function (event, xhr) {
        if (xhr.status === 401 || xhr.status === 403) {
            window.location.reload();
        }
    });
})();
