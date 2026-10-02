package kr.co.jihun.guisample.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /user/rag/sessions/{sessionId}/messages 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageListResponse
{
    /** 조회한 세션 ID */
    private String sessionId;
    /** 대화 목록 (chat_content_id ASC — 시간순) */
    private List<MessageRow> rows;
}
