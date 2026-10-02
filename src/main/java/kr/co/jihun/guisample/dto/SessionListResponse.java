package kr.co.jihun.guisample.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /user/rag/sessions 응답. jqGrid 관용구(page/total/records/rows)와 동일 형태.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListResponse
{
    /** 현재 페이지 번호 */
    private int page;
    /** 전체 페이지 수 */
    private int total;
    /** 전체 레코드 수 */
    private int records;
    /** 세션 목록 (create_date DESC — 최신 상단) */
    private List<SessionRow> rows;
}
