package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.dto.WorkflowTaskDTO;
import kr.co.jihun.guisample.service.WorkflowService;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 워크플로우 REST 엔드포인트.
 *
 * <p><b>사용자 귀속</b> — 담당자 조건으로 쓸 로그인 사용자명은 이 컨트롤러가
 * {@link LoginUserSession} 에서 꺼내 서비스로 넘긴다. 서비스/엔진은 세션을 알지 못한다
 * (project/chat 쪽과 같은 규칙).
 *
 * <p>목록 응답은 jqGrid 계약 {@code { page, total, records, rows[] }} 를 따른다.
 */
@RestController
@RequestMapping("/user/workflow")
@RequiredArgsConstructor
public class WorkflowRestController
{
    private final WorkflowService workflowService;
    private final LoginUserSession loginUserSession;

    /** 해당 단계에 머물러 있는 내 태스크 목록. */
    @GetMapping("/tasks")
    public Map<String, Object> taskList(
            @RequestParam String step,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int rows)
    {
        String userName = loginUserSession.requireUserName();
        workflowService.validateStepKey(step);

        /* 총건수와 목록이 같은 조건을 봐야 페이징이 어긋나지 않으므로 건수를 먼저 센다. */
        long totalRecords = workflowService.countTasks(step, userName);
        int  totalPages   = (totalRecords == 0) ? 0 : (int) Math.ceil((double) totalRecords / rows);

        List<WorkflowTaskDTO> list =
                workflowService.selectTaskList(step, userName, (page - 1) * rows, rows);

        return gridResponse(page, totalPages, totalRecords, list);
    }

    /** 종료된 내 태스크 목록 — 스텝3 화면 하단에서 종료 단계 도달을 확인하는 용도. */
    @GetMapping("/finished")
    public Map<String, Object> finishedList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int rows)
    {
        String userName = loginUserSession.requireUserName();

        long totalRecords = workflowService.countFinishedTasks(userName);
        int  totalPages   = (totalRecords == 0) ? 0 : (int) Math.ceil((double) totalRecords / rows);

        List<WorkflowTaskDTO> list =
                workflowService.selectFinishedTaskList(userName, (page - 1) * rows, rows);

        return gridResponse(page, totalPages, totalRecords, list);
    }

    /**
     * 태스크 생성 — task_master 에 업무 정보를 남기고 프로세스를 시작해 스텝1 태스크 한 건을 만든다.
     *
     * @param body {@code taskName} — 태스크 이름(필수). 비어 있으면 서비스가 거부한다.
     */
    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody(required = false) Map<String, String> body)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            String taskName = (body != null) ? body.get("taskName") : null;

            WorkflowTaskDTO created =
                    workflowService.startTask(loginUserSession.requireUserName(), taskName);

            result.put("success",  true);
            result.put("taskId",   created.getTaskId());
            result.put("taskName", created.getTaskName());
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 현재 단계 완료 → 다음 단계로 이동. 스텝3 을 완료하면 종료된다.
     *
     * @param flowableTaskId 엔진이 단계마다 새로 발급하는 태스크 ID (목록 행의 rowId)
     * @param body {@code step} — 요청을 보낸 화면의 단계. 태스크의 실제 단계와 다르면 거부된다.
     */
    @PostMapping("/tasks/{flowableTaskId}/complete")
    public Map<String, Object> completeTask(@PathVariable String flowableTaskId,
                                            @RequestBody(required = false) Map<String, String> body)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            String step = (body != null) ? body.get("step") : null;
            String reachedStepName =
                    workflowService.completeTask(flowableTaskId, step, loginUserSession.requireUserName());

            result.put("success",    true);
            result.put("nextStep",   reachedStepName);
            result.put("finished",   "종료".equals(reachedStepName));
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> gridResponse(int page, int totalPages, long totalRecords,
                                             List<WorkflowTaskDTO> rows)
    {
        Map<String, Object> result = new HashMap<>();
        result.put("page",    page);
        result.put("total",   totalPages);
        result.put("records", totalRecords);
        result.put("rows",    rows);
        return result;
    }
}
