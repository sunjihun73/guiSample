package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * knowledge_files 테이블 매핑 DTO.
 * 업로드된 지식파일의 메타 정보(파일ID·카테고리·파일명·생성/수정 정보)를 담는다.
 * file_id 는 vector_store 청크 metadata 의 parent_document_id 와 동일한 UUID 다.
 */
@Getter
@Setter
@ToString
public class KnowledgeFileDTO
{
    private String fileId;
    private String categoryId;
    private String fileName;
    private String updateUserId;
    private String updateDate;
    private String createUserId;
    private String createDate;

    /** 목록 조회 표시용 — category_master 조인 결과(테이블 컬럼 아님). */
    private String categoryName;
}
