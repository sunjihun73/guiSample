package kr.co.jihun.guisample.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.jihun.guisample.filter.KeycloakUserProvisioningFilter;
import kr.co.jihun.guisample.service.UserMasterService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * Keycloak(OIDC) SSO 보안 설정.
 *
 * <ul>
 *   <li>로그인: {@code /oauth2/authorization/keycloak} → Keycloak 인증 → {@code /login/oauth2/code/keycloak} 콜백</li>
 *   <li>로그아웃: POST {@code /logout} → 로컬 세션 무효화 → Keycloak end_session_endpoint → {@link #POST_LOGOUT_REDIRECT_URI}</li>
 *   <li>CSRF: 기본(세션 저장소) 유지 — JSP 가 meta 태그로 토큰을 내리고 {@code /js/csrf.js} 가 모든 비-GET 요청 헤더에 실어 보낸다.</li>
 *   <li>사용자 자동 등록: 로그인 후 첫 요청에서 {@link KeycloakUserProvisioningFilter} 가 user_master 에 사용자를 등록한다.</li>
 * </ul>
 *
 * 클라이언트 등록 정보(client-id/secret, issuer-uri)는 {@code application.yaml} 의
 * {@code spring.security.oauth2.client} 하위에 있다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{
    /** Keycloak 로그아웃 후 최종 착지 지점. Keycloak 클라이언트의 Valid post logout redirect URIs 와 일치해야 한다. */
    private static final String POST_LOGOUT_REDIRECT_URI = "http://mytechtest.jihun.com/";

    /** 인증 없이 접근 가능한 경로 — 랜딩 페이지와 정적 리소스. */
    private static final String[] PUBLIC_PATHS = {
            "/", "/error", "/favicon.ico",
            "/css/**", "/js/**", "/tinymce/**", "/actuator/health/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository,
                                                   UserMasterService userMasterService)
            throws Exception
    {
        http
                .authorizeHttpRequests(auth -> auth
                        /* JSP 뷰는 /WEB-INF/views/** 로 forward 되는데 이 내부 dispatch 까지 인가 대상이 된다.
                           (컨테이너가 /WEB-INF 직접 접근은 막으므로, 최초 REQUEST 인가만으로 충분하다.) */
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        /* false: 로그인 전에 요청했던 URL 이 있으면 그쪽을 우선한다. */
                        .defaultSuccessUrl("/user/dashboard", false))
                .logout(logout -> logout
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                /* AJAX 요청은 로그인 페이지로 302 시키지 않고 401 로 끊는다.
                   (기본 동작은 /login 으로 리다이렉트 → jQuery/fetch 가 로그인 HTML 을 JSON 으로 파싱하려다 실패한다.)
                   클라이언트는 /js/csrf.js 의 전역 핸들러가 401/403 을 받으면 페이지를 새로고침해 정상 로그인 흐름을 탄다. */
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                SecurityConfig::isAjaxRequest))
                /* 인가를 통과한 뒤(체인 마지막) 실행 — Keycloak 최초 로그인 사용자를 user_master 에 등록한다.
                   Boot 의 Filter 빈 자동 등록으로 인한 이중 실행을 피하려고 빈이 아닌 직접 생성으로 넣는다. */
                .addFilterAfter(new KeycloakUserProvisioningFilter(userMasterService),
                                AuthorizationFilter.class);

        return http.build();
    }

    /** 로컬 로그아웃 후 Keycloak 의 RP-Initiated Logout 까지 이어서 수행한다. */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository)
    {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri(POST_LOGOUT_REDIRECT_URI);
        return handler;
    }

    /** jQuery($.ajax/jqGrid) 와 fetch(JSON/SSE) 호출을 브라우저 페이지 이동과 구분한다. */
    private static boolean isAjaxRequest(HttpServletRequest request)
    {
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With")))
        {
            return true;
        }

        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null
                && (accept.contains(MediaType.APPLICATION_JSON_VALUE)
                 || accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE));
    }
}
