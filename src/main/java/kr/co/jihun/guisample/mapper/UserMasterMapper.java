package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.UserMasterDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMasterMapper
{
    /** Keycloak sub 로 사용자 단건 조회. 없으면 null. */
    UserMasterDTO selectUserMasterByKeycloakId(@Param("keycloakId") String keycloakId);

    /**
     * 사용자 등록. 동일 keycloak_id 가 이미 있으면 INSERT 하지 않는다(0 반환).
     *
     * @return 반영된 행 수 (1 = 신규 등록, 0 = 이미 존재)
     */
    int insertUserMaster(UserMasterDTO userMaster);
}
