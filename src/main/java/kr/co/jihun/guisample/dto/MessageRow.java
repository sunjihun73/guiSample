package kr.co.jihun.guisample.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 세션 대화 복원 응답 행. ChatDetailDTO 를 프론트 계약 필드명으로 매핑.
 * model/promptTokens/completionTokens 는 assistant 행에서만 non-null 이며,
 * user 행에서는 null 이다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageRow
{
    /** "user" | "assistant" */
    private String role;
    /** 발화 본문 (chat_detail.chat_content) */
    private String content;
    /** assistant 행만: 모델명 (예: "gpt-4o-mini"). user 행은 null */
    private String model;
    /** assistant 행만: 프롬프트 토큰 수. 미확보/user 행이면 null */
    private Integer promptTokens;
    /** assistant 행만: 완료 토큰 수. 미확보/user 행이면 null */
    private Integer completionTokens;
    /** 생성 시각 문자열. JDBC getString 포맷 그대로 (예: "2026-07-08 10:12:00.0") */
    private String createDate;
}
