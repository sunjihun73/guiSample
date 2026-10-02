package kr.co.jihun.guisample.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import kr.co.jihun.guisample.dto.SessionUser;
import kr.co.jihun.guisample.dto.UserMasterDTO;
import kr.co.jihun.guisample.service.UserMasterService;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keycloak 로그인 사용자를 user_master 에 자동 등록하고, 그 사용자 정보를 <b>세션에 보관</b>하는 필터.
 *
 * <p>동작 조건 — 인증된 OIDC 사용자이면서 이 세션에 아직 로그인 사용자 객체가 없는 경우에만
 * {@code keycloak_id}({@code sub}) 로 조회하고, 없으면 등록한 뒤
 * {@link SessionUser} 를 세션에 넣는다.
 *
 * <p><b>실행 시점</b> — OAuth2 콜백({@code /login/oauth2/code/keycloak}) 요청은
 * {@code OAuth2LoginAuthenticationFilter} 가 리다이렉트로 끝내므로 이 필터까지 오지 않는다.
 * 따라서 실제 등록은 <b>로그인 직후 첫 인증 요청</b>(기본값 {@code /user/dashboard})에서 일어난다.
 *
 * <p><b>세션 가드</b> — 매 요청마다 DB 를 조회하지 않도록 세션의 {@link SessionUser} 존재 자체를
 * 가드로 쓴다. 별도 플래그를 두지 않으므로 "가드는 남았는데 사용자 객체는 없는" 상태가 생기지 않는다.
 * 저장은 성공했을 때만 이뤄지므로, DB 장애로 실패하면 다음 요청에서 자동으로 재시도된다.
 *
 * <p><b>실패 정책</b> — 등록 실패가 로그인 자체를 막지 않도록 예외를 삼키고 로그만 남긴다
 * (fail-open). 여기서 예외를 던지면 화면 전체가 500 이 된다. 다만 세션 사용자 없이 데이터 조회를
 * 시도하면 {@code LoginUserSession} 이 명시적으로 실패시킨다 — 조회 조건이 조용히 빠져
 * 다른 사용자 데이터가 노출되는 것보다 낫기 때문이다.
 *
 * <p>Spring Boot 가 {@code Filter} 타입 빈을 서블릿 컨테이너에 자동 등록해 이중 실행되는 것을
 * 막기 위해 이 클래스는 빈으로 만들지 않고 {@code SecurityConfig} 에서 직접 생성해 체인에 넣는다.
 */
@Slf4j
@RequiredArgsConstructor
public class KeycloakUserProvisioningFilter extends OncePerRequestFilter
{
    private final UserMasterService userMasterService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException
    {
        OidcUser oidcUser = currentOidcUser();

        if (oidcUser != null)
        {
            /* 세션이 없으면 만들지 않는다(false) — 인증 상태면 로그인 시점에 이미 존재한다. */
            HttpSession session = request.getSession(false);

            if (session != null && LoginUserSession.find(session) == null)
            {
                provision(oidcUser, session);
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 등록(또는 조회)을 시도하고, 성공하면 세션에 로그인 사용자 객체를 넣는다. */
    private void provision(OidcUser oidcUser, HttpSession session)
    {
        try
        {
            UserMasterDTO user = userMasterService.loadOrRegister(oidcUser);

            if (user == null)
            {
                /* sub 클레임이 비어 있는 비정상 토큰 — 세션에 담을 사용자가 없다. */
                log.warn("user_master 사용자를 확보하지 못해 세션 저장을 건너뛴다.");
                return;
            }

            LoginUserSession.store(session, SessionUser.from(user));
            log.debug("세션에 로그인 사용자 저장 (userId={}, userName={})",
                    user.getUserId(), user.getUserName());
        }
        catch (Exception e)
        {
            /* 세션에 저장하지 않으므로 다음 요청에서 다시 시도된다. */
            log.error("user_master 사용자 자동 등록 실패 (keycloakId={})", oidcUser.getSubject(), e);
        }
    }

    /** 현재 인증 주체가 OIDC 사용자면 반환, 아니면(익명 포함) null. */
    private OidcUser currentOidcUser()
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated())
        {
            return null;
        }

        return (authentication.getPrincipal() instanceof OidcUser oidcUser) ? oidcUser : null;
    }
}
