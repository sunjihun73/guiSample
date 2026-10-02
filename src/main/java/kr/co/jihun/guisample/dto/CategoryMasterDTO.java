package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class CategoryMasterDTO
{
  private String categoryId;
  private String categoryName;
  private String updateUserId;
  private String updateDate;
  private String createUserId;
  private String createDate;
}
