package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.HashMap;
import java.util.List;

@Mapper
public interface ProjectMasterMapper
{
    List<ProjectMasterDTO> selectProjectMasterList(HashMap<String, Object> param);

    ProjectMasterDTO selectProjectMaster(HashMap<String, Object> param);

    int countProjectMaster(HashMap<String, Object> param);

    int insertProjectMaster(ProjectMasterDTO projectMaster);

    /**
     * 프로젝트 수정. 소유자(create_user_id)가 일치할 때만 반영된다.
     *
     * <p>소유자 값을 DTO 가 아니라 <b>별도 파라미터</b>로 받는 이유 — DTO 는 {@code @RequestBody}
     * 로 바인딩되므로, DTO 의 createUserId 를 조건에 쓰면 클라이언트가 보낸 값으로 소유자 검사를
     * 통과시킬 수 있다. 세션에서 온 값만 조건에 들어가도록 경로를 분리한다.
     *
     * @param projectMaster 수정할 내용
     * @param createUserId  로그인 사용자명(user_master.user_name)
     * @return 반영된 행 수 (0 = 대상 없음 또는 소유자 불일치)
     */
    int updateProjectMaster(@Param("project") ProjectMasterDTO projectMaster,
                            @Param("createUserId") String createUserId);
}