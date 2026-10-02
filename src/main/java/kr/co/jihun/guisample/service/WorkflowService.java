package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.WorkflowTaskDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 워크플로우(스텝1 → 스텝2 → 스텝3 → 종료) 비즈니스 계층.
 *
 * <p><b>저장소가 둘이다.</b> 진행 상태는 Flowable 엔진 DB(flowable-db)에, 업무 정보(태스크 이름)는
 * 앱 DB(pgvector)의 {@code task_master} 에 있다. 두 쪽을 잇는 키는 {@code taskId} 하나다
 * (엔진 쪽에서는 business key 이자 프로세스 변수 {@code taskNo}). 물리 FK 는 없다 — ADR 0006.
 *
 * <p><b>엔진 변수 이름이 다른 이유</b> — 엔진 변수는 {@code taskNo} 로 두고 앱 계층에서는
 * {@code taskId} 로 부른다. 이미 흐르고 있는 인스턴스의 변수명을 바꾸면 그 건들의 값이 사라지므로
 * 경계에서만 이름을 옮긴다({@link #toDto}).
 *
 * <p><b>소유권</b> — {@code user_master.user_name} 을 프로세스 변수 {@code initiator} 로 넘기고,
 * BPMN 의 세 userTask 가 이 값을 assignee 로 받는다. 목록·완료·이력 조회가 모두 assignee(또는
 * initiator 변수)로 걸러지고, 이름 조회도 {@code task_owner_user_id} 로 한 번 더 걸린다.
 * 기준값은 컨트롤러가 요청 스레드에서 꺼내 인자로 넘겨준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService
{
    /** BPMN 의 process id — {@code processes/simple-task.bpmn20.xml}. */
    public static final String PROCESS_KEY = "simpleTaskProcess";

    /** 담당자(= user_master.user_name)를 담는 프로세스 변수명. BPMN 의 {@code ${initiator}} 와 같은 이름이어야 한다. */
    public static final String VAR_INITIATOR = "initiator";

    /** 태스크 ID 를 담는 프로세스 변수명. business key 및 {@code task_master.task_id} 와 같은 값이다. */
    public static final String VAR_TASK_ID = "taskNo";

    /** 종료 단계의 가상 키 — BPMN 의 userTask 가 아니라 화면 표기를 위한 값이다. */
    public static final String STEP_END = "end";

    /** 화면·REST 가 쓰는 단계 키. BPMN userTask id 와 1:1 이며, 순서가 곧 진행 순서다. */
    private static final List<String> STEP_KEYS = List.of("step1", "step2", "step3");

    /** 단계 키 → 화면 표시명. */
    private static final Map<String, String> STEP_NAMES = Map.of(
            "step1", "스텝1",
            "step2", "스텝2",
            "step3", "스텝3",
            STEP_END, "종료");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final TaskMasterService taskMasterService;

    /**
     * 태스크 한 건 생성 — 업무 정보를 먼저 저장하고, 프로세스를 시작해 첫 단계(스텝1) 태스크를 만든다.
     *
     * <p><b>순서에 의미가 있다.</b> DB 가 둘이라 한 트랜잭션으로 묶이지 않는다(2PC 없음).
     * task_master INSERT 를 앱 트랜잭션 안에서 먼저 하고 그 뒤에 엔진을 시작하므로,
     * 엔진 시작이 실패하면 앱 트랜잭션이 함께 롤백돼 <b>양쪽 모두 남지 않는다.</b>
     * 반대 순서였다면 이름 없는 태스크가 엔진에 남는다.
     * (앱 트랜잭션 커밋 자체가 실패하는 좁은 구간만 이름 없는 태스크가 생길 수 있고,
     *  그 경우에도 목록은 이름 칸만 비운 채 정상 동작한다.)
     *
     * @param userName 로그인 사용자명 — 세 단계 전부의 assignee 이자 task_master 소유자
     * @param taskName 태스크 이름 (필수)
     * @return 생성된 스텝1 태스크
     */
    @Transactional
    public WorkflowTaskDTO startTask(String userName, String taskName)
    {
        String taskId = generateTaskId();

        taskMasterService.createTaskMaster(taskId, taskName, userName);

        Map<String, Object> variables = new HashMap<>();
        variables.put(VAR_INITIATOR, userName);
        variables.put(VAR_TASK_ID, taskId);

        /* business key 에도 같은 값을 넣는다 — 엔진 쪽에서 업무 식별자로 검색할 수 있게. */
        ProcessInstance instance =
                runtimeService.startProcessInstanceByKey(PROCESS_KEY, taskId, variables);

        log.info("워크플로우 태스크 생성 (taskId={}, taskName={}, processInstanceId={}, userName={})",
                taskId, taskName, instance.getProcessInstanceId(), userName);

        Task task = taskService.createTaskQuery()
                .processInstanceId(instance.getProcessInstanceId())
                .taskAssignee(userName)
                .singleResult();

        return toDto(task, taskId, taskName);
    }

    /**
     * 해당 단계에 머물러 있는 내 태스크 건수. 목록과 같은 조건을 봐야 하므로 목록 조회보다 먼저 호출한다.
     */
    public long countTasks(String stepKey, String userName)
    {
        return taskQuery(stepKey, userName).count();
    }

    /**
     * 해당 단계에 머물러 있는 내 태스크 목록(최신 순). 태스크 이름은 task_master 에서 한 번에 채운다.
     *
     * @param startRow 0-base 시작 행
     * @param pageSize 페이지 크기
     */
    public List<WorkflowTaskDTO> selectTaskList(String stepKey, String userName, int startRow, int pageSize)
    {
        List<Task> tasks = taskQuery(stepKey, userName)
                .includeProcessVariables()
                .orderByTaskCreateTime().desc()
                .listPage(startRow, pageSize);

        List<String> taskIds = new ArrayList<>(tasks.size());
        for (Task task : tasks)
        {
            taskIds.add(taskIdOf(task.getProcessVariables(), null));
        }

        /* 이름은 페이지에 보이는 건만 한 번에 읽는다(행마다 조회하지 않는다). */
        Map<String, String> nameById = taskMasterService.selectTaskNameMap(taskIds, userName);

        List<WorkflowTaskDTO> rows = new ArrayList<>(tasks.size());
        for (Task task : tasks)
        {
            String taskId = taskIdOf(task.getProcessVariables(), null);
            rows.add(toDto(task, taskId, nameById.get(taskId)));
        }
        return rows;
    }

    /**
     * 현재 단계를 완료하고 다음 단계로 넘긴다. 스텝3 을 완료하면 프로세스가 종료된다.
     *
     * @param flowableTaskId  완료할 Flowable 태스크 ID (단계마다 새로 발급된다)
     * @param expectedStepKey 요청한 화면의 단계 — 실제 태스크 단계와 다르면 거부한다
     *                        (목록이 오래돼 이미 넘어간 태스크를 다시 완료하는 것을 막는다)
     * @param userName        로그인 사용자명 — 소유자가 아니면 대상이 없는 것으로 처리된다
     * @return 완료 후 도달한 단계의 표시명 (스텝2 / 스텝3 / 종료)
     */
    public String completeTask(String flowableTaskId, String expectedStepKey, String userName)
    {
        /* 소유자 조건을 쿼리에 함께 건다 — 남의 태스크는 조회 자체가 안 되므로 "없음"으로 떨어진다. */
        Task task = taskService.createTaskQuery()
                .taskId(flowableTaskId)
                .taskAssignee(userName)
                .singleResult();

        if (task == null)
        {
            throw new IllegalArgumentException("완료할 태스크를 찾을 수 없습니다. 목록을 새로고침해 주세요.");
        }

        if (expectedStepKey != null && !expectedStepKey.equals(task.getTaskDefinitionKey()))
        {
            throw new IllegalStateException(
                    "이미 " + stepName(task.getTaskDefinitionKey()) + " 단계로 넘어간 태스크입니다. 목록을 새로고침해 주세요.");
        }

        String processInstanceId = task.getProcessInstanceId();
        taskService.complete(flowableTaskId);

        /* 다음 단계 태스크가 없으면 종료 이벤트까지 흘러간 것이다. */
        Task next = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();

        String reachedStep = (next != null) ? next.getTaskDefinitionKey() : STEP_END;

        log.info("워크플로우 단계 완료 (flowableTaskId={}, {} → {}, userName={})",
                flowableTaskId, task.getTaskDefinitionKey(), reachedStep, userName);

        return stepName(reachedStep);
    }

    /** 종료된 내 태스크 건수. */
    public long countFinishedTasks(String userName)
    {
        return finishedQuery(userName).count();
    }

    /** 종료된 내 태스크 목록(최근 종료 순). */
    public List<WorkflowTaskDTO> selectFinishedTaskList(String userName, int startRow, int pageSize)
    {
        List<HistoricProcessInstance> instances = finishedQuery(userName)
                .includeProcessVariables()
                .orderByProcessInstanceEndTime().desc()
                .listPage(startRow, pageSize);

        List<String> taskIds = new ArrayList<>(instances.size());
        for (HistoricProcessInstance instance : instances)
        {
            taskIds.add(taskIdOf(instance.getProcessVariables(), instance.getBusinessKey()));
        }

        Map<String, String> nameById = taskMasterService.selectTaskNameMap(taskIds, userName);

        List<WorkflowTaskDTO> rows = new ArrayList<>(instances.size());
        for (HistoricProcessInstance instance : instances)
        {
            String taskId = taskIdOf(instance.getProcessVariables(), instance.getBusinessKey());

            rows.add(WorkflowTaskDTO.builder()
                    .processInstanceId(instance.getId())
                    .taskId(taskId)
                    .taskName(nameById.get(taskId))
                    .stepKey(STEP_END)
                    .stepName(stepName(STEP_END))
                    .assignee(userName)
                    .createDate(format(instance.getStartTime()))
                    .stepDate(format(instance.getEndTime()))
                    .build());
        }
        return rows;
    }

    /** 단계 키가 BPMN 에 정의된 값인지 확인한다. 화면이 넘긴 값을 그대로 쿼리에 넣지 않기 위함. */
    public void validateStepKey(String stepKey)
    {
        if (!STEP_KEYS.contains(stepKey))
        {
            throw new IllegalArgumentException("알 수 없는 단계입니다: " + stepKey);
        }
    }

    /** 단계 표시명. 알 수 없는 키면 키 자체를 돌려준다(화면이 빈 칸이 되지 않도록). */
    public static String stepName(String stepKey)
    {
        return STEP_NAMES.getOrDefault(stepKey, stepKey);
    }

    /* ── 내부 ───────────────────────────────────────────── */

    private org.flowable.task.api.TaskQuery taskQuery(String stepKey, String userName)
    {
        validateStepKey(stepKey);
        return taskService.createTaskQuery()
                .processDefinitionKey(PROCESS_KEY)
                .taskDefinitionKey(stepKey)
                .taskAssignee(userName);
    }

    private org.flowable.engine.history.HistoricProcessInstanceQuery finishedQuery(String userName)
    {
        return historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(PROCESS_KEY)
                .variableValueEquals(VAR_INITIATOR, userName)
                .finished();
    }

    private WorkflowTaskDTO toDto(Task task, String taskId, String taskName)
    {
        return WorkflowTaskDTO.builder()
                .taskId(taskId)
                .taskName(taskName)
                .flowableTaskId(task.getId())
                .processInstanceId(task.getProcessInstanceId())
                .stepKey(task.getTaskDefinitionKey())
                .stepName(stepName(task.getTaskDefinitionKey()))
                .assignee(task.getAssignee())
                .createDate(format(task.getCreateTime()))
                .stepDate(format(task.getCreateTime()))
                .build();
    }

    /** 프로세스 변수에서 태스크 ID 를 꺼낸다. 변수가 없으면 business key 등 대체값을 쓴다. */
    private String taskIdOf(Map<String, Object> variables, String fallback)
    {
        Object value = (variables != null) ? variables.get(VAR_TASK_ID) : null;
        return (value != null) ? String.valueOf(value) : fallback;
    }

    /** 태스크 ID — 정렬해도 생성 순서가 보이도록 시각을 앞에 둔다. 뒤 4자리는 동시 생성 충돌 방지용. */
    private String generateTaskId()
    {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String suffix    = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return "WF-" + timestamp + "-" + suffix;
    }

    private String format(Date date)
    {
        if (date == null)
        {
            return "";
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(TIMESTAMP_FORMAT);
    }
}
