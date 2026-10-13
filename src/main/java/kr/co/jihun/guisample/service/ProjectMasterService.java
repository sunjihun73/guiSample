package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.mapper.ProjectMasterMapper;
import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * project_master 비즈니스 계층.
 *
 * <p><b>사용자 귀속</b> — 조회 조건(createUserId)은 컨트롤러가 세션에서 꺼내 param 에 담아 준다.
 * 이 계층은 세션을 알지 못하며, 저장 시 찍을 사용자명만 인자로 받는다.
 * 조회 조건 주입 지점을 컨트롤러 한 곳으로 못 박아 두면 필터가 빠진 경로를 찾기 쉽다.
 */
@Service
@RequiredArgsConstructor
public class ProjectMasterService
{
    private final ProjectMasterMapper projectMasterMapper;

    public List<ProjectMasterDTO> selectProjectMasterList(HashMap<String, Object> param)
    {
        return projectMasterMapper.selectProjectMasterList(param);
    }

    public int countProjectMaster(HashMap<String, Object> param)
    {
        return projectMasterMapper.countProjectMaster(param);
    }

    /**
     * 프로젝트 단건 조회.
     *
     * @param projectId   조회할 프로젝트 ID
     * @param createUserId 로그인 사용자명(user_master.user_name) — 소유자가 아니면 null 이 반환된다
     */
    public ProjectMasterDTO selectProjectMaster(String projectId, String createUserId)
    {
        HashMap<String, Object> param = new HashMap<>();
        param.put("projectId", projectId);
        param.put("createUserId", createUserId);
        return projectMasterMapper.selectProjectMaster(param);
    }

    /**
     * 프로젝트 등록.
     *
     * @param userName  로그인 사용자명 — create_user_id 로 저장되며 이후 조회 조건의 기준이 된다
     * @param ownerName 로그인 사용자 표시 이름 — project_owner_name(담당자)으로 저장된다
     */
    public ProjectMasterDTO createProjectMaster(ProjectMasterDTO projectMaster, String userName, String ownerName)
    {
        // project_id 는 GUID 로 서비스에서 생성
        projectMaster.setProjectId(UUID.randomUUID().toString());
        // 담당자는 등록자 본인으로 고정 — 요청 본문의 값은 무시한다
        projectMaster.setProjectOwnerName(ownerName);
        /* 생성자/수정자는 세션의 로그인 사용자 (create_date/update_date 는 NOW() 로 매퍼에서 처리).
           create_user_id 가 목록 조회 필터의 기준이므로 여기 값이 틀리면 등록 직후 목록에서 사라진다. */
        projectMaster.setCreateUserId(userName);
        projectMaster.setUpdateUserId(userName);

        projectMasterMapper.insertProjectMaster(projectMaster);
        return projectMaster;
    }

    /**
     * 프로젝트 수정. 소유자(create_user_id)가 일치할 때만 반영된다.
     *
     * @param userName 로그인 사용자명 — update_user_id 로 저장되고, 동시에 소유자 조건으로도 쓰인다
     * @return 반영된 행 수 (0 = 대상 없음 또는 남의 프로젝트)
     */
    public int updateProjectMaster(ProjectMasterDTO projectMaster, String userName)
    {
        // 수정자는 세션의 로그인 사용자 (update_date 는 NOW() 로 매퍼에서 처리)
        projectMaster.setUpdateUserId(userName);

        /* 소유자 조건은 DTO 가 아니라 세션에서 온 userName 으로 별도 전달한다. */
        return projectMasterMapper.updateProjectMaster(projectMaster, userName);
    }

    /**
     * 프로젝트 삭제. 소유자(create_user_id)가 일치할 때만 지운다.
     *
     * @param userName 로그인 사용자명 — 소유자 조건
     * @return 삭제된 행 수 (0 = 대상 없음 또는 남의 프로젝트)
     */
    public int deleteProjectMaster(String projectId, String userName)
    {
        return projectMasterMapper.deleteProjectMaster(projectId, userName);
    }
}
