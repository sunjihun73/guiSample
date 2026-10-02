package kr.co.jihun.guisample.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * BM25 키워드 검색 결과 1건.
 *
 * <p>하이브리드 검색에서 dense(pgvector) 결과와 RRF 로 융합되며,
 * 융합 키는 {@link #chunkId}(= vector_store.id = Spring AI {@code Document.getId()}) 다.
 *
 * <p>{@link #content} 를 함께 담는 이유: 키워드 쪽에서만 올라온 청크는 dense 결과에
 * 대응하는 {@code Document} 가 없어 컨텍스트 본문의 출처가 필요하다.
 */
@Getter
@Setter
@ToString
public class KeywordHitDTO
{
    /** 청크 id (vector_store.id, UUID). RRF 융합 키. */
    private String chunkId;

    /** 부모 지식파일 id (metadata.parent_document_id = knowledge_files.file_id). */
    private String parentDocumentId;

    /** 청크 본문 (vector_store.content). */
    private String content;

    /** BM25 원점수. 순위 결정용. 질의 길이에 비례하므로 임계값 판정에 쓰면 안 된다. */
    private Double bm25Score;

    /**
     * 질의 bigram 수로 나눈 정규화 BM25 점수. <b>게이트 판정은 이 값으로 한다.</b>
     * 원점수는 질의가 길수록 커져서 무관한 긴 질의가 정답인 짧은 질의를 이긴다(실측).
     */
    private Double bm25Norm;
}
