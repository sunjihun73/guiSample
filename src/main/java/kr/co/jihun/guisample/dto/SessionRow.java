package kr.co.jihun.guisample.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 세션 목록 응답 행. ChatMasterDTO(chatSessionId/chatTitleName/createDate)를
 * 프론트 계약 필드명(sessionId/title/createDate)으로 매핑한 경량 객체.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionRow
{
    /** 세션 ID (chat_master.chat_session_id) */
    private String sessionId;
    /** 세션 제목 (chat_master.chat_title_name) */
    private String title;
    /** 생성 시각 문자열. JDBC getString 포맷 그대로 (예: "2026-07-08 10:11:12.0") */
    private String createDate;
}
