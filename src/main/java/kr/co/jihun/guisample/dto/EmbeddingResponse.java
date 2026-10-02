package kr.co.jihun.guisample.dto;

/**
 * 지식파일 업로드 → 청킹 → 임베딩 → pgvector 적재 + knowledge_files 저장 결과 응답 DTO.
 * 성공/실패 모두 HTTP 200 + 이 shape 으로 통일하여 프론트 분기를 단순화한다.
 *
 * @param success    처리 성공 여부
 * @param fileId     생성된 파일 ID(UUID). vector_store 청크 metadata 의 parent_document_id 와 동일
 * @param categoryId 지식파일이 속한 카테고리 ID (metadata.source 값과 동일)
 * @param fileName   업로드된 원본 파일명
 * @param chunkCount vector_store 에 적재된 청크 수
 * @param message    사용자 표시용 메시지(성공/실패 사유)
 */
public record EmbeddingResponse(
        boolean success,
        String fileId,
        String categoryId,
        String fileName,
        int chunkCount,
        String message
)
{
}
