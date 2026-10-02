package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ProjectMasterDTO
{
    private String projectId;
    private String projectName;
    private String projectOwnerName;
    private String projectDescription;
    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;
}