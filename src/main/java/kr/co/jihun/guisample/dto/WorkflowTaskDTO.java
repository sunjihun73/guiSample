package kr.co.jihun.guisample.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 워크플로우 목록 화면(스텝1/2/3 · 종료)의 행 하나.
 *
 * <p>Flowable 의 {@code Task}(진행 중) 와 {@code HistoricProcessInstance}(종료) 에
 * {@code task_master} 의 업무 정보(이름)를 합쳐 화면이 쓰는 한 가지 모양으로 맞춘 객체다.
 *
 * <p><b>id 가 둘인 이유</b> — {@link #taskId} 는 사람이 읽는 업무 식별자(엔진 business key,
 * {@code task_master.task_id})이고, {@link #flowableTaskId} 는 엔진이 매 단계마다 새로 발급하는
 * 내부 태스크 id 다. 완료 요청에는 후자가 필요하고, 화면 표시와 두 DB 연결에는 전자를 쓴다.
 *
 * <p>날짜는 {@code java.util.Date} 가 아니라 {@code "yyyy-MM-dd HH:mm:ss"} 문자열로 내린다 —
 * 다른 목록 응답(ProjectMasterDTO 등)이 이미 문자열이라 화면 포맷 로직을 하나로 유지한다.
 */
@Getter
@Builder
@ToString
public class WorkflowTaskDTO
{
    /** 태스크 ID (예: WF-20260922-231632-31C6). 엔진 business key = {@code task_master.task_id}. */
    private final String taskId;

    /** 태스크 이름({@code task_master.task_name}). 업무 정보 행이 없으면 null 이다. */
    private final String taskName;

    /** Flowable 태스크 ID — 완료 요청의 식별자. 단계마다 새로 발급되며, 종료된 건은 값이 없다. */
    private final String flowableTaskId;

    /** 프로세스 인스턴스 ID — 한 건의 태스크가 스텝1~종료까지 유지하는 엔진 내부 식별자. */
    private final String processInstanceId;

    /** 현재 단계 키 — step1 / step2 / step3, 종료된 건은 {@code end}. */
    private final String stepKey;

    /** 현재 단계 표시명 — 스텝1 / 스텝2 / 스텝3 / 종료. */
    private final String stepName;

    /** 담당자(user_master.user_name). 조회 필터의 기준값이기도 하다. */
    private final String assignee;

    /** 태스크(프로세스 인스턴스) 생성 시각. */
    private final String createDate;

    /** 현재 단계로 넘어온 시각. 종료 건은 프로세스 종료 시각. */
    private final String stepDate;
}
