package kr.co.jihun.guisample.session;

import jakarta.servlet.http.HttpSession;
import kr.co.jihun.guisample.dto.SessionUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 세션에 보관된 로그인 사용자({@link SessionUser})에 접근하는 유일한 통로.
 *
 * <p>컨트롤러/서비스는 {@code SecurityContextHolder} 나 {@code OidcUser} 를 직접 뒤지지 않고
 * 이 컴포넌트만 사용한다. 사용자 정보의 출처를 한 곳으로 모아 두어야
 * 저장 위치가 바뀌어도 호출측을 고치지 않는다.
 *
 * <p><b>HttpSession 주입</b> — 싱글턴 빈이지만 Spring 이 요청마다 실제 세션으로 위임하는
 * 프록시를 주입하므로 스레드 안전하다.
 *
 * <p><b>주의</b> — 이 프록시는 <b>요청 스레드에 바인딩</b>된다. 비동기 스레드
 * (예: {@code Schedulers.boundedElastic()} 에서 도는 SSE 저장 로직) 안에서 호출하면
 * 요청 컨텍스트가 없어 실패한다. 그런 경우 <b>컨트롤러 메서드 본문에서 값을 먼저 꺼내</b>
 * 파라미터로 넘겨야 한다.
 */
@Component
@RequiredArgsConstructor
public class LoginUserSession
{
    private final HttpSession httpSession;

    /** 세션에 사용자 정보를 저장한다. 로그인 직후 필터가 호출한다. */
    public static void store(HttpSession session, SessionUser user)
    {
        session.setAttribute(SessionUser.SESSION_KEY, user);
    }

    /** 세션에 저장된 사용자 정보를 읽는다. 없으면 null. */
    public static SessionUser find(HttpSession session)
    {
        Object value = session.getAttribute(SessionUser.SESSION_KEY);
        return (value instanceof SessionUser sessionUser) ? sessionUser : null;
    }

    /** 현재 요청의 로그인 사용자. 없으면 null. */
    public SessionUser find()
    {
        return find(httpSession);
    }

    /**
     * 현재 요청의 로그인 사용자. 없으면 예외.
     *
     * <p>인증이 필요한 경로에서는 필터가 반드시 세션을 채우므로 정상 흐름에서는 발생하지 않는다.
     * 조용히 null 을 돌려주면 조회 조건이 통째로 빠져 <b>다른 사용자 데이터가 노출</b>되므로
     * 값이 없으면 명시적으로 실패시킨다.
     */
    public SessionUser require()
    {
        SessionUser sessionUser = find();

        if (sessionUser == null)
        {
            throw new IllegalStateException(
                    "세션에 로그인 사용자 정보가 없습니다. 인증이 필요한 경로인지 확인하세요.");
        }
        return sessionUser;
    }

    /**
     * 조회 조건에 사용할 사용자명(user_master.user_name).
     *
     * @throws IllegalStateException 세션에 사용자 정보가 없거나 user_name 이 비어 있는 경우
     */
    public String requireUserName()
    {
        SessionUser sessionUser = require();
        String userName = sessionUser.getUserName();

        if (userName == null || userName.isBlank())
        {
            throw new IllegalStateException(
                    "로그인 사용자의 user_name 이 비어 있습니다. (userId=" + sessionUser.getUserId() + ")");
        }
        return userName;
    }
}
