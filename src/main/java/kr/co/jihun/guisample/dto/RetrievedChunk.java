package kr.co.jihun.guisample.dto;

/**
 * 하이브리드 검색으로 최종 선택된 청크 1건.
 *
 * @param chunkId      vector_store.id (dense/keyword 융합 키)
 * @param text         컨텍스트로 주입할 본문
 * @param denseRank    dense 결과에서의 1-base 순위 (없으면 null)
 * @param denseScore   dense 코사인 유사도 (없으면 null)
 * @param keywordRank  BM25 결과에서의 1-base 순위 (없으면 null)
 * @param bm25Score    BM25 점수 (없으면 null)
 * @param rrfScore     융합 점수
 */
public record RetrievedChunk(
        String chunkId,
        String text,
        Integer denseRank,
        Double denseScore,
        Integer keywordRank,
        Double bm25Score,
        double rrfScore)
{
}
