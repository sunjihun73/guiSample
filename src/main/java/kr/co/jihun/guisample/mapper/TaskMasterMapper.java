package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.TaskMasterDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMasterMapper
{
    /** 태스크 업무 정보 등록. 스텝1 의 태스크 생성에서만 호출된다. */
    int insertTaskMaster(TaskMasterDTO taskMaster);

    /**
     * 태스크 ID 목록으로 업무 정보를 한 번에 조회한다(목록 화면의 N+1 방지).
     *
     * @param taskIds          조회할 태스크 ID 목록 — <b>비어 있으면 호출하지 말 것</b>(IN () 는 문법 오류)
     * @param taskOwnerUserId  소유자 = user_master.user_name
     */
    List<TaskMasterDTO> selectTaskMasterListByIds(@Param("taskIds") List<String> taskIds,
                                                  @Param("taskOwnerUserId") String taskOwnerUserId);
}
