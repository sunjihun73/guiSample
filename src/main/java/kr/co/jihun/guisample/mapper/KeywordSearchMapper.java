package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.KeywordHitDTO;
import org.apache.ibatis.annotations.Mapper;

import java.util.HashMap;
import java.util.List;

/**
 * BM25 키워드 검색 매퍼.
 *
 * <p>스키마·함수는 {@code src/main/resources/db/bm25_hybrid_schema.sql}(수동 적용)에 있다.
 * 미적용 상태에서는 {@code content_bigram} 컬럼이 없어 조회가 실패하므로,
 * 호출부({@code HybridRetrievalService})가 try/catch 로 dense-only 폴백한다.
 */
@Mapper
public interface KeywordSearchMapper
{
    /**
     * 소유자 범위 BM25 상위 청크 조회.
     *
     * <p><b>질의는 원문 그대로 넘긴다.</b> bigram 변환은 SQL 이
     * {@code bm25_bigram_tsvector()} 로 수행한다 — Java 에서 토크나이즈하면 문서 색인
     * 쪽 토크나이저와 조용히 어긋날 수 있다. 같은 함수를 쓰는 것이 검색이 계속
     * 동작하게 만드는 핵심이다.
     *
     * @param param query(필수, 원문 질의), createUserId(필수, 소유권 필터),
     *              categoryId(선택), candidateK(필수, LIMIT)
     */
    List<KeywordHitDTO> selectBm25TopChunks(HashMap<String, Object> param);

    /**
     * df/N/avgdl 스냅샷 재계산.
     *
     * <p>XML 에서 {@code <select>} 로 선언돼 있다 — {@code <update>} 는
     * {@code executeUpdate()} 를 호출해 {@code SELECT func()} 에서
     * "A result was returned when none was expected" 로 실패한다.
     *
     * @return 기록된 term 수
     */
    int refreshBm25Stats();
}
