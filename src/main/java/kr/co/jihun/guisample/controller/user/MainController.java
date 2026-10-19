package kr.co.jihun.guisample.controller.user;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/user")
public class MainController
{
    @GetMapping({ "/", "/dashboard" })
    public String dashboard()
    {
        return "user/dashboard";
    }

    @GetMapping({ "/projects" })
    public String projects()
    {
        return "user/projects";
    }

    @GetMapping({ "/rag" })
    public String rag()
    {
        return "user/rag";
    }

    @GetMapping({ "/embedding" })
    public String embedding()
    {
        return "user/embedding";
    }

    /* 저장소끌어오기 — 파일 트리 조회는 RepositoryRestController(GET /user/repository/files). */
    @GetMapping({ "/repositories" })
    public String repositories()
    {
        return "user/repositories";
    }

    /* ── 워크플로우 (Flowable) ──────────────────────────────
       화면 3개가 각각 BPMN 의 userTask(step1/step2/step3)에 대응한다.
       진행 중인 태스크 조회·완료는 WorkflowRestController 가 담당한다. */

    @GetMapping({ "/workflow/step1" })
    public String workflowStep1()
    {
        return "user/workflow/step1";
    }

    @GetMapping({ "/workflow/step2" })
    public String workflowStep2()
    {
        return "user/workflow/step2";
    }

    @GetMapping({ "/workflow/step3" })
    public String workflowStep3()
    {
        return "user/workflow/step3";
    }

    /* 종료는 BPMN 의 userTask 가 아니라 끝난 프로세스를 조회하는 화면이다(HistoryService). */
    @GetMapping({ "/workflow/ends" })
    public String workflowEnds()
    {
        return "user/workflow/ends";
    }
}
