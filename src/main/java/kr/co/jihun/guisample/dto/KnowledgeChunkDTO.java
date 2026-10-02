package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * vector_store 청크 조회용 DTO.
 * 특정 지식파일(parent_document_id = knowledge_files.file_id)에 속한 청크 1건을 담는다.
 * 물리 컬럼은 id/content 뿐이며, chunkIndex/totalChunks/categoryId/parentDocumentId 는
 * metadata(json) 에서 추출한다.
 */
@Getter
@Setter
@ToString
public class KnowledgeChunkDTO
{
    /** 청크 자신의 id (vector_store.id, UUID). */
    private String chunkId;

    /** 부모 지식파일 id (metadata.parent_document_id = knowledge_files.file_id). */
    private String parentDocumentId;

    /** 카테고리 id (metadata.category_id). */
    private String categoryId;

    /** 파일 내 청크 순번 (metadata.chunk_index, 0-base). */
    private Integer chunkIndex;

    /** 파일의 전체 청크 수 (metadata.total_chunks). */
    private Integer totalChunks;

    /** 청크 본문 (vector_store.content). */
    private String content;
}
