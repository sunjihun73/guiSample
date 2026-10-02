package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.TaskMasterDTO;
import kr.co.jihun.guisample.mapper.TaskMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * task_master 비즈니스 계층 — 워크플로우 태스크의 <b>업무 정보(이름)</b>만 다룬다.
 *
 * <p>진행 상태는 Flowable 엔진 DB 에 있고 이쪽은 앱 DB(pgvector)다. 두 저장소를 잇는 키는
 * {@code taskId}(= 엔진 business key) 하나뿐이며 물리 FK 는 없다.
 *
 * <p><b>사용자 귀속</b> — 조회 조건으로 쓸 사용자명은 호출측이 넘겨준다(다른 서비스와 같은 규칙).
 */
@Service
@RequiredArgsConstructor
public class TaskMasterService
{
    /** task_name 컬럼 길이(varchar(200)). 초과 입력은 잘라서 저장한다. */
    private static final int MAX_TASK_NAME_LENGTH = 200;

    private final TaskMasterMapper taskMasterMapper;

    /**
     * 태스크 업무 정보 등록.
     *
     * @param taskId   태스크 ID — 엔진 business key 와 같은 값이어야 한다
     * @param taskName 태스크 이름 (필수)
     * @param userName 소유자 = user_master.user_name
     */
    public TaskMasterDTO createTaskMaster(String taskId, String taskName, String userName)
    {
        String trimmed = (taskName != null) ? taskName.trim() : "";

        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException("태스크 이름을 입력하세요.");
        }
        if (trimmed.length() > MAX_TASK_NAME_LENGTH)
        {
            trimmed = trimmed.substring(0, MAX_TASK_NAME_LENGTH);
        }

        TaskMasterDTO taskMaster = new TaskMasterDTO();
        taskMaster.setTaskId(taskId);
        taskMaster.setTaskName(trimmed);
        taskMaster.setTaskOwnerUserId(userName);
        taskMaster.setCreateUserId(userName);
        taskMaster.setUpdateUserId(userName);

        taskMasterMapper.insertTaskMaster(taskMaster);
        return taskMaster;
    }

    /**
     * 태스크 ID → 태스크 이름 맵. 목록 화면이 행마다 조회하지 않도록 한 번에 읽는다.
     *
     * <p>엔진에는 있는데 task_master 에 행이 없는 태스크(이 기능 이전에 만들어진 건, 또는
     * 등록이 중간에 끊긴 건)는 <b>맵에서 빠진다.</b> 호출측은 이름이 없을 수 있다고 보고
     * 처리해야 한다 — 목록 자체가 깨지면 안 되기 때문이다.
     */
    public Map<String, String> selectTaskNameMap(List<String> taskIds, String userName)
    {
        if (taskIds == null || taskIds.isEmpty())
        {
            return Collections.emptyMap();
        }

        List<TaskMasterDTO> rows = taskMasterMapper.selectTaskMasterListByIds(taskIds, userName);

        Map<String, String> nameById = new HashMap<>();
        for (TaskMasterDTO row : rows)
        {
            nameById.put(row.getTaskId(), row.getTaskName());
        }
        return nameById;
    }
}
