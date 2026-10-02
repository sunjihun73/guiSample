package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * user_master 테이블 DTO.
 *
 * <p>{@code user_id} 는 애플리케이션이 생성한 UUID(PK)이고,
 * {@code keycloak_id} 는 Keycloak ID Token 의 {@code sub} 클레임이다.
 * 사용자를 식별하는 실제 기준은 {@code keycloak_id} 다.
 */
@Getter
@Setter
@ToString
public class UserMasterDTO
{
    private String userId;
    private String keycloakId;
    private String userName;
    private String email;
    private String displayName;
    private String status;
    private String lastLoginDate;
    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;
}
