package kr.co.jihun.guisample.advisor;

/**
 * RAG 검색 튜닝 파라미터 묶음. 실제 값은 {@code SpringAiConfig} 가 정하며,
 * 그쪽 javadoc 에 각 값의 실측 근거가 있다.
 *
 * @param topK                        최종 컨텍스트로 쓸 청크 수
 * @param candidateK                  각 검색기(dense/keyword)에서 가져올 후보 수
 * @param rrfK                        RRF 상수 k (표준값 60)
 * @param candidateSimilarityThreshold dense 후보 편입 하한 (코사인)
 * @param gateSimilarityThreshold      "관련 문서 없음" 판정의 dense 하한 (코사인)
 * @param gateKeywordNormScore         같은 판정의 keyword 하한 (질의 bigram 수로 정규화한 BM25).
 *                                     dense 가 놓친 어휘성 질의를 구제하는 OR 조건이다.
 */
public record RagRetrievalSettings(
        int topK,
        int candidateK,
        int rrfK,
        double candidateSimilarityThreshold,
        double gateSimilarityThreshold,
        double gateKeywordNormScore)
{
}
