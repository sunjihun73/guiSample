package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ChatMasterDTO
{
    private String chatSessionId;
    private String chatTitleName;
    private String chatOwnerUserId;
    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;
}
