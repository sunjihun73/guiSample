package kr.co.jihun.guisample.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /user/rag/sessions 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCreateResponse
{
    /** 성공 여부 */
    private boolean success;
    /** 생성된 세션 ID (성공 시) */
    private String sessionId;
    /** 생성된 세션 제목 (성공 시, 기본 "새로운채팅") */
    private String title;
    /** 실패 시 메시지 (성공 시 null) */
    private String message;
}
