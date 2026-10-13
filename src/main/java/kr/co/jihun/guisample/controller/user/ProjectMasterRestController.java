package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.dto.RepoSyncResult;
import kr.co.jihun.guisample.service.ProjectMasterService;
import kr.co.jihun.guisample.service.RepoSyncService;
import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * project_master REST 엔드포인트.
 *
 * <p><b>사용자 귀속</b> — 조회 조건에 들어갈 로그인 사용자명은 이 컨트롤러에서
 * {@link LoginUserSession} 으로 세션에서 꺼내 param 에 담는다.
 * 서비스/매퍼는 세션을 알지 못하므로, 필터가 빠진 경로가 있다면 이 파일만 보면 된다.
 */
@RestController
@RequestMapping("/user/project")
@RequiredArgsConstructor
public class ProjectMasterRestController
{
    private final ProjectMasterService projectMasterService;
    private final RepoSyncService repoSyncService;
    private final LoginUserSession loginUserSession;

    @GetMapping("/projects")
    public Map<String, Object> projectList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int rows,
            @RequestParam(required = false) String projectName)
    {
        HashMap<String, Object> param = new HashMap<>();
        if (projectName != null && !projectName.isEmpty())
        {
            param.put("projectName", projectName);
        }

        /* 로그인 사용자 조건 — 총건수와 목록이 같은 조건을 봐야 페이징이 어긋나지 않으므로
           count 호출보다 반드시 먼저 넣는다. */
        param.put("createUserId", loginUserSession.requireUserName());

        int totalRecords = projectMasterService.countProjectMaster(param);
        int totalPages   = totalRecords == 0 ? 0 : (int) Math.ceil((double) totalRecords / rows);

        param.put("startRow", (page - 1) * rows);
        param.put("pageSize", rows);

        List<ProjectMasterDTO> list = projectMasterService.selectProjectMasterList(param);

        Map<String, Object> result = new HashMap<>();
        result.put("page",    page);
        result.put("total",   totalPages);
        result.put("records", totalRecords);
        result.put("rows",    list);
        return result;
    }

    @GetMapping("/projects/{projectId}")
    public Map<String, Object> projectDetail(@PathVariable String projectId)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            /* 단건 조회에도 로그인 사용자 조건을 건다 — 남의 프로젝트는 "찾을 수 없음"으로 처리된다. */
            ProjectMasterDTO project = projectMasterService.selectProjectMaster(
                    projectId, loginUserSession.requireUserName());
            if (project == null)
            {
                result.put("success", false);
                result.put("message", "프로젝트를 찾을 수 없습니다.");
            }
            else
            {
                result.put("success", true);
                result.put("project", project);
            }
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/projects")
    public Map<String, Object> saveProject(@RequestBody ProjectMasterDTO projectMaster)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            /* create_user_id 에 세션 사용자명을 찍어야 등록 직후 목록 조회 조건에 걸린다.
               담당자(project_owner_name)도 화면 입력값이 아니라 세션 사용자의 표시 이름으로 저장한다. */
            ProjectMasterDTO saved = projectMasterService.createProjectMaster(
                    projectMaster,
                    loginUserSession.requireUserName(),
                    loginUserSession.require().displayNameOrUserName());
            result.put("success",   true);
            result.put("projectId", saved.getProjectId());
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PatchMapping("/projects/{projectId}")
    public Map<String, Object> updateProject(
            @PathVariable String projectId,
            @RequestBody ProjectMasterDTO projectMaster)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            projectMaster.setProjectId(projectId);
            int updated = projectMasterService.updateProjectMaster(
                    projectMaster, loginUserSession.requireUserName());
            result.put("success",   updated > 0);
            result.put("projectId", projectId);
            if (updated == 0)
            {
                result.put("message", "수정 대상 프로젝트를 찾을 수 없습니다.");
            }
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 프로젝트 삭제 — 남의 프로젝트이거나 이미 없으면 success=false. */
    @DeleteMapping("/projects/{projectId}")
    public Map<String, Object> deleteProject(@PathVariable String projectId)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            int deleted = projectMasterService.deleteProjectMaster(projectId, loginUserSession.requireUserName());
            result.put("success",   deleted > 0);
            result.put("projectId", projectId);
            if (deleted == 0)
            {
                result.put("message", "삭제 대상 프로젝트를 찾을 수 없습니다.");
            }
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * REPO동기화 — 소유자와 무관하게 전체 프로젝트마다 repo_url 저장소의 최근 커밋을 읽어
     * 커밋 메시지의 {req_id} 가 일치하는 커밋의 변경 파일로 file_names 를 갱신한다.
     * 저장소별 실패는 결과 항목(FAILED)으로 돌려주고, 전체가 실패할 때만 success=false 다.
     */
    @PostMapping("/projects/repo-sync")
    public Map<String, Object> syncRepository()
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            RepoSyncResult sync = repoSyncService.syncFileNames(loginUserSession.requireUserName());
            result.put("success", true);
            result.put("result",  sync);
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
