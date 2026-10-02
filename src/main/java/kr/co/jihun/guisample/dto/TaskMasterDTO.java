package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * task_master — 워크플로우 태스크의 업무 정보.
 *
 * <p>진행 상태(어느 단계에 있는지, 누가 언제 완료했는지)는 여기 없다. 그쪽은 전부
 * Flowable 엔진 DB 가 갖는다. 이 테이블은 엔진이 모르는 <b>업무 정보(이름)</b>만 보관한다.
 *
 * <p>{@code taskId} 는 Flowable 프로세스 인스턴스의 <b>business key</b>(및 프로세스 변수
 * {@code taskNo})와 같은 값이다. DB 가 서로 달라 물리 FK 를 걸 수 없으므로, 이 값이
 * 두 저장소를 잇는 유일한 키다.
 */
@Getter
@Setter
@ToString
public class TaskMasterDTO
{
    /** 태스크 ID (예: WF-20260922-231632-31C6) — 엔진 business key 와 같은 값. */
    private String taskId;

    /** 태스크 이름 — 스텝1 에서 사용자가 입력한다. */
    private String taskName;

    /** 소유자 = user_master.user_name. 조회 필터 기준. */
    private String taskOwnerUserId;

    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;
}
