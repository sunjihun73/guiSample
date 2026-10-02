package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.UserMasterDTO;
import kr.co.jihun.guisample.mapper.UserMasterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * user_master 비즈니스 계층.
 *
 * <p>Keycloak 최초 로그인 시 사용자를 자동 등록(provisioning)한다.
 * 식별 기준은 PK(user_id)가 아니라 {@code keycloak_id}(ID Token 의 {@code sub})다.
 * user_id 는 애플리케이션이 생성하는 UUID 이므로 PK 충돌로는 중복을 판별할 수 없다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMasterService
{
    /** 신규 등록 사용자의 기본 상태값. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** user_master 의 문자열 컬럼 길이(varchar(100)) — 초과 값은 잘라서 저장한다. */
    private static final int MAX_COLUMN_LENGTH = 100;

    private final UserMasterMapper userMasterMapper;

    /**
     * Keycloak 사용자에 대응하는 user_master 행을 돌려준다. 없으면 등록한 뒤 돌려준다.
     *
     * <p>조회 → 없으면 INSERT 순서이며, INSERT 문에도 {@code NOT EXISTS} 가드가 있어
     * 조회와 INSERT 사이에 다른 요청이 먼저 넣더라도 중복 행이 생기지 않는다.
     * 다만 완전한 동시성 차단은 {@code keycloak_id} 에 UNIQUE 인덱스가 있어야 보장된다.
     *
     * <p>반환값이 필요한 이유 — 호출측(로그인 필터)이 이 행으로 세션 사용자 객체를 만든다.
     * 조회 조건에 쓰이는 {@code user_name} 은 DB 에 저장된 값을 그대로 써야
     * 저장 시점의 truncate 등으로 토큰 클레임과 어긋나는 일이 없다.
     *
     * @param oidcUser 인증된 Keycloak 사용자
     * @return user_master 행. sub 클레임이 비어 있으면 null.
     */
    @Transactional
    public UserMasterDTO loadOrRegister(OidcUser oidcUser)
    {
        String keycloakId = oidcUser.getSubject();

        if (keycloakId == null || keycloakId.isBlank())
        {
            log.warn("Keycloak sub 클레임이 비어 있어 사용자 등록을 건너뛴다.");
            return null;
        }

        UserMasterDTO existing = userMasterMapper.selectUserMasterByKeycloakId(keycloakId);

        if (existing != null)
        {
            return existing;
        }

        UserMasterDTO user = toUserMaster(oidcUser, keycloakId);
        int inserted = userMasterMapper.insertUserMaster(user);

        if (inserted == 0)
        {
            /* NOT EXISTS 가드에 걸린 경우 — 동시 요청이 먼저 등록했다는 뜻이라 정상 상황이다.
               이때 로컬에서 만든 user 객체의 user_id 는 실제 저장된 값이 아니므로 반드시 재조회한다. */
            log.debug("사용자가 이미 등록되어 있어 INSERT 를 건너뛴다. (keycloakId={})", keycloakId);
            return userMasterMapper.selectUserMasterByKeycloakId(keycloakId);
        }

        log.info("Keycloak 최초 로그인 — 사용자 등록 완료 (userId={}, keycloakId={}, userName={})",
                user.getUserId(), keycloakId, user.getUserName());
        return user;
    }

    /** OidcUser 클레임을 user_master 행으로 변환한다. */
    private UserMasterDTO toUserMaster(OidcUser oidcUser, String keycloakId)
    {
        /* user_id 는 Keycloak 값이 아니라 애플리케이션이 생성한 UUID 를 PK 로 사용한다. */
        String userId = UUID.randomUUID().toString();

        String preferredUsername = oidcUser.getPreferredUsername();
        String fullName = oidcUser.getFullName();

        UserMasterDTO user = new UserMasterDTO();
        user.setUserId(userId);
        user.setKeycloakId(truncate(keycloakId));
        user.setUserName(truncate(preferredUsername));
        user.setEmail(truncate(oidcUser.getEmail()));
        user.setDisplayName(truncate(fullName != null ? fullName : preferredUsername));
        user.setStatus(STATUS_ACTIVE);
        /* 자동 등록이므로 생성자/수정자는 자기 자신(user_id)으로 남긴다. */
        user.setCreateUserId(userId);
        user.setUpdateUserId(userId);

        return user;
    }

    /** varchar(100) 초과 값으로 INSERT 가 실패하지 않도록 자른다. */
    private String truncate(String value)
    {
        if (value == null || value.length() <= MAX_COLUMN_LENGTH)
        {
            return value;
        }
        return value.substring(0, MAX_COLUMN_LENGTH);
    }
}
