package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.advisor.RagRetrievalSettings;
import kr.co.jihun.guisample.dto.KeywordHitDTO;
import kr.co.jihun.guisample.dto.RetrievedChunk;
import kr.co.jihun.guisample.mapper.KeywordSearchMapper;
import kr.co.jihun.guisample.mapper.KnowledgeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dense(pgvector) + keyword(BM25) 하이브리드 검색을 RRF 로 융합한다.
 *
 * <p><b>왜 필요한가:</b> dense 임베딩은 고유명사에 구조적으로 약하다. 실측상 코퍼스에
 * 실제로 존재하는 고유명사 "테오리아" 의 dense 최고 유사도가 0.372 로, 무관 문서 상한
 * (0.247)과 사실상 구분되지 않았다. 임베딩 모델을 올려도 해결되지 않아 키워드 검색을
 * 병행한다. 자세한 수치는 {@code src/docs/measurements.md}.
 *
 * <p><b>소유권:</b> dense·keyword 양쪽 모두 {@code knowledge_files.create_user_id} 로
 * 제한한다. {@code vector_store} 에는 사용자 컬럼이 없으므로 상위 엔터티의 소유권을
 * 확인하는 CLAUDE.md 의 규칙을 따른다.
 *
 * <p><b>스레드 주의:</b> 이 서비스는 {@code Schedulers.boundedElastic()} 에서 호출될 수
 * 있다. {@code LoginUserSession} 은 요청 스레드 바인딩 프록시이므로 여기서 만지면 안 되며,
 * {@code userName} 은 반드시 컨트롤러가 꺼내 파라미터로 넘겨야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService
{
    /** 청크 metadata 에서 부모 지식파일을 가리키는 키. dense 소유권 필터 대상. */
    private static final String PARENT_DOCUMENT_ID = "parent_document_id";

    private final VectorStore vectorStore;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KeywordSearchMapper keywordSearchMapper;

    /**
     * 하이브리드 검색을 수행한다.
     *
     * @param query      사용자 질문 원문
     * @param categoryId 검색을 한정할 카테고리 (null/공백이면 전체)
     * @param userName   소유권 필터 기준 (user_master.user_name). 필수.
     * @param settings   튜닝 파라미터
     * @return 융합 상위 청크. 관련 문서가 없다고 판정되면 빈 목록.
     */
    public List<RetrievedChunk> retrieve(String query, String categoryId, String userName,
                                         RagRetrievalSettings settings)
    {
        // 1) 소유 파일 목록. 비어 있으면 두 검색 모두 생략한다.
        //    (Spring AI 의 in() 은 빈 리스트에 "()" 를 내보내 jsonpath 파싱 에러를 낸다.)
        List<String> ownedFileIds = selectOwnedFileIds(userName, categoryId);
        if (ownedFileIds.isEmpty())
        {
            log.debug("소유한 지식파일이 없어 검색을 건너뛴다 - userName={}", userName);
            return List.of();
        }

        // 2) dense — 소유 파일로 사전 필터. 사후 필터는 쓰지 않는다(조용한 손실 방지).
        List<Document> dense = searchDense(query, ownedFileIds, settings);

        // 3) keyword — 스키마 미적용 등으로 실패해도 dense-only 로 계속한다.
        //    게이트보다 "먼저" 돌려야 한다. dense 가 약한 어휘성 질의를 BM25 가 구제할 수 있어야 하는데,
        //    게이트를 먼저 통과시키면 그 기회 자체가 사라진다(고유명사 질의가 통째로 버려진다).
        List<KeywordHitDTO> keyword = searchKeyword(query, userName, categoryId, settings);

        // 4) 답변 게이트. 융합 점수는 순위 인공물이라 "관련 문서가 있었는가" 를 담지 못하므로
        //    두 검색기의 원 점수로 판정한다. 어느 한쪽이라도 확신하면 통과시킨다.
        if (!isRelevant(dense, keyword, settings))
        {
            log.debug("게이트 미통과 - query={} denseMax={} bm25NormMax={}",
                    query, maxDenseScore(dense), maxBm25Norm(keyword));
            return List.of();
        }

        // 5) RRF 융합
        List<RetrievedChunk> fused = fuse(dense, keyword, settings);

        if (log.isDebugEnabled())
        {
            fused.forEach(c -> log.debug(
                    "RRF | chunk={} denseRank={} denseScore={} kwRank={} bm25={} rrf={} | {}",
                    c.chunkId(), c.denseRank(), c.denseScore(), c.keywordRank(), c.bm25Score(),
                    String.format("%.5f", c.rrfScore()), preview(c.text())));
        }
        return fused;
    }

    // ------------------------------------------------------------------
    // 각 검색기
    // ------------------------------------------------------------------

    private List<String> selectOwnedFileIds(String userName, String categoryId)
    {
        HashMap<String, Object> param = new HashMap<>();
        param.put("createUserId", userName);
        if (categoryId != null && !categoryId.isBlank())
        {
            param.put("categoryId", categoryId);
        }
        List<String> ids = knowledgeFileMapper.selectOwnedFileIdList(param);
        return ids == null ? List.of() : ids;
    }

    /**
     * category_id 메타데이터 필터는 걸지 않는다 — 소유 파일 목록을 뽑을 때 이미
     * {@code knowledge_files.category_id} 로 걸렀고, 두 값은 같은 업로드 호출에서
     * 같은 변수로 쓰이므로 어긋날 수 없다. 필터 하나로 jsonpath 길이가 절반이 된다.
     */
    private List<Document> searchDense(String query, List<String> ownedFileIds,
                                       RagRetrievalSettings settings)
    {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(settings.candidateK())
                .similarityThreshold(settings.candidateSimilarityThreshold())
                .filterExpression(new FilterExpressionBuilder()
                        .in(PARENT_DOCUMENT_ID, ownedFileIds.toArray())
                        .build())
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        return documents == null ? List.of() : documents;
    }

    private List<KeywordHitDTO> searchKeyword(String query, String userName, String categoryId,
                                              RagRetrievalSettings settings)
    {
        HashMap<String, Object> param = new HashMap<>();
        param.put("query", query);
        param.put("createUserId", userName);
        param.put("categoryId", categoryId);
        param.put("candidateK", settings.candidateK());
        try
        {
            List<KeywordHitDTO> hits = keywordSearchMapper.selectBm25TopChunks(param);
            return hits == null ? List.of() : hits;
        }
        catch (Exception e)
        {
            // 가장 흔한 원인: db/bm25_hybrid_schema.sql 미적용(content_bigram 컬럼 없음).
            // dense 만으로도 답은 나오므로 검색 자체를 실패시키지 않는다.
            log.error("BM25 키워드 검색 실패 - dense 단독으로 진행한다. "
                    + "db/bm25_hybrid_schema.sql 적용 여부를 확인할 것.", e);
            return List.of();
        }
    }

    /**
     * "관련 문서가 있었는가" 판정. dense 와 keyword 중 <b>어느 한쪽이라도</b> 확신하면 통과.
     *
     * <p>dense 단독 판정은 고유명사 질의에서 자책골이 된다 — 실측상 "테오리아" 는 dense 최고
     * 0.20 으로 게이트에 걸리지만 BM25 는 정답을 1·2위로 정확히 집어낸다. 하이브리드를 붙여놓고
     * 키워드 쪽 승리를 dense 점수로 버리는 셈이라 OR 로 판정한다.
     *
     * <p>keyword 쪽은 <b>반드시 정규화 점수</b>({@code bm25Norm})로 본다. BM25 원점수는 질의가
     * 길수록 항이 많아 커져서, 무관한 긴 질의(7.15)가 정답인 짧은 질의(2.84)보다 높게 나온다(실측).
     */
    private boolean isRelevant(List<Document> dense, List<KeywordHitDTO> keyword,
                               RagRetrievalSettings settings)
    {
        Double denseMax = maxDenseScore(dense);
        if (denseMax != null && denseMax >= settings.gateSimilarityThreshold())
        {
            return true;
        }
        Double bm25Max = maxBm25Norm(keyword);
        return bm25Max != null && bm25Max >= settings.gateKeywordNormScore();
    }

    private static Double maxDenseScore(List<Document> dense)
    {
        return dense.stream().map(Document::getScore).filter(java.util.Objects::nonNull)
                .max(Double::compareTo).orElse(null);
    }

    private static Double maxBm25Norm(List<KeywordHitDTO> keyword)
    {
        return keyword.stream().map(KeywordHitDTO::getBm25Norm).filter(java.util.Objects::nonNull)
                .max(Double::compareTo).orElse(null);
    }

    // ------------------------------------------------------------------
    // RRF 융합
    // ------------------------------------------------------------------

    /**
     * Reciprocal Rank Fusion: {@code score(d) = Σ 1/(k + rank)}.
     *
     * <p>순위만 쓰므로 스케일이 전혀 다른 코사인 유사도와 BM25 점수를 정규화 없이 합칠 수 있다.
     * 동점은 {@code min(denseRank, keywordRank)} → {@code chunkId} 로 풀어 결정적으로 정렬한다
     * (측정 재현성).
     */
    private List<RetrievedChunk> fuse(List<Document> dense, List<KeywordHitDTO> keyword,
                                      RagRetrievalSettings settings)
    {
        Map<String, Accumulator> byChunkId = new LinkedHashMap<>();

        for (int i = 0; i < dense.size(); i++)
        {
            Document d = dense.get(i);
            Accumulator acc = byChunkId.computeIfAbsent(d.getId(), k -> new Accumulator());
            acc.text = d.getText();
            acc.denseRank = i + 1;
            acc.denseScore = d.getScore();
            acc.rrf += 1.0 / (settings.rrfK() + acc.denseRank);
        }

        for (int i = 0; i < keyword.size(); i++)
        {
            KeywordHitDTO hit = keyword.get(i);
            Accumulator acc = byChunkId.computeIfAbsent(hit.getChunkId(), k -> new Accumulator());
            if (acc.text == null)
            {
                // dense 에 없던 청크 — 본문 출처가 키워드 결과뿐이다.
                acc.text = hit.getContent();
            }
            acc.keywordRank = i + 1;
            acc.bm25Score = hit.getBm25Score();
            acc.rrf += 1.0 / (settings.rrfK() + acc.keywordRank);
        }

        List<RetrievedChunk> fused = new ArrayList<>(byChunkId.size());
        byChunkId.forEach((chunkId, acc) -> fused.add(new RetrievedChunk(
                chunkId, acc.text, acc.denseRank, acc.denseScore,
                acc.keywordRank, acc.bm25Score, acc.rrf)));

        fused.sort(Comparator
                .comparingDouble(RetrievedChunk::rrfScore).reversed()
                .thenComparingInt(HybridRetrievalService::bestRank)
                .thenComparing(RetrievedChunk::chunkId));

        return fused.size() > settings.topK() ? List.copyOf(fused.subList(0, settings.topK())) : fused;
    }

    private static int bestRank(RetrievedChunk c)
    {
        int dense = c.denseRank() == null ? Integer.MAX_VALUE : c.denseRank();
        int keyword = c.keywordRank() == null ? Integer.MAX_VALUE : c.keywordRank();
        return Math.min(dense, keyword);
    }

    private static String preview(String text)
    {
        if (text == null) { return ""; }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40);
    }

    /** 융합 중간 상태. record 로 만들기엔 가변이라 private 클래스로 둔다. */
    private static final class Accumulator
    {
        private String text;
        private Integer denseRank;
        private Double denseScore;
        private Integer keywordRank;
        private Double bm25Score;
        private double rrf;
    }
}
