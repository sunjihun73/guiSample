package kr.co.jihun.guisample.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * HTTP 세션에 보관하는 로그인 사용자 정보.
 *
 * <p>Keycloak 인증 직후 {@code KeycloakUserProvisioningFilter} 가 user_master 행을 읽어
 * 이 객체를 세션에 넣고, 이후 컨트롤러/서비스는 Keycloak 토큰이나 DB 를 다시 건드리지 않고
 * {@code LoginUserSession} 을 통해 여기서만 사용자 정보를 얻는다.
 *
 * <p><b>불변</b>으로 둔 이유 — 세션 객체는 여러 요청이 공유하므로 중간에 값이 바뀌면
 * 조회 조건이 요청마다 달라지는 추적 불가능한 버그가 된다. 사용자 정보가 바뀌면
 * 세션 속성을 통째로 교체한다.
 *
 * <p>{@link Serializable} — 세션 직렬화(톰캣 재시작 시 세션 복원, 세션 클러스터링)를 대비한다.
 */
@Getter
@ToString
@Builder
public class SessionUser implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 세션 속성 키. 다른 코드가 문자열을 중복 정의하지 않도록 여기서만 선언한다. */
    public static final String SESSION_KEY = "LOGIN_USER";

    /** user_master.user_id — 애플리케이션이 생성한 UUID(PK). */
    private final String userId;

    /** user_master.keycloak_id — ID Token 의 sub 클레임. 사용자 식별의 실제 기준. */
    private final String keycloakId;

    /**
     * user_master.user_name — Keycloak preferred_username.
     * <p>조회 조건에 실리는 값이 바로 이 필드다
     * (project_master.create_user_id, chat_master.chat_owner_user_id).
     */
    private final String userName;

    /** user_master.display_name — 화면 표시용 이름. 조회 조건에는 쓰지 않는다. */
    private final String displayName;

    /** user_master.email. */
    private final String email;

    /**
     * 화면에 보이는 사용자명 — display_name, 비어 있으면 user_name.
     * <p>상단바 표시 이름과 같은 규칙이다. 프로젝트 담당자(project_owner_name)도 이 값으로 저장한다.
     * 조회 조건에는 쓰지 않는다(조회 조건은 {@link #userName}).
     */
    public String displayNameOrUserName()
    {
        return (displayName != null && !displayName.isBlank()) ? displayName : userName;
    }

    /** user_master 행을 세션 보관용 객체로 변환한다. */
    public static SessionUser from(UserMasterDTO user)
    {
        return SessionUser.builder()
                .userId(user.getUserId())
                .keycloakId(user.getKeycloakId())
                .userName(user.getUserName())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .build();
    }
}
