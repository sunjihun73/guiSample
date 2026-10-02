package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ChatDetailDTO
{
    private Long chatContentId;
    private String chatSessionId;
    private String chatContent;
    private String role;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;
}
