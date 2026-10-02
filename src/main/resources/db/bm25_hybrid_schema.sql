-- =====================================================================
-- bm25_hybrid_schema.sql : BM25 키워드 검색 + RRF 하이브리드 retrieval 스키마
-- 설계: ~/.claude/plans/bm25-sleepy-lampson.md, src/docs/adr/0005-hybrid-bm25-rrf.md
-- ---------------------------------------------------------------------
-- ⚠ 이 파일은 "사용자 승인 후 수동 적용" 대상이다.
--    애플리케이션 부팅/마이그레이션으로 자동 실행하지 않는다.
--    DBA/사용자가 검토 후 psql 등으로 직접 실행할 것.
--
-- ⚠ 배포 순서: 이 SQL 을 코드보다 "먼저" 적용해야 한다.
--    (미적용 상태로 신 코드가 뜨면 content_bigram 이 없어 키워드 검색이 실패한다.
--     HybridRetrievalService 가 try/catch 로 dense-only 폴백하므로 치명상은 아니다.)
--
-- ⚠ ALTER TABLE ... ADD COLUMN ... GENERATED 는 테이블 전체 재작성이다.
--    2026-09-17 기준 318행이라 순간이지만, 코퍼스가 크면 잠금 시간을 감안할 것.
--
-- ⚠ bm25_bigram_tsvector() 정의를 나중에 바꾸면 "기존 생성열 값은 재계산되지 않는다".
--    토크나이저를 수정했다면 두 생성열을 DROP 후 재생성해야 한다.
--
-- ⚠ spring.ai.vectorstore.pgvector.remove-existing-vector-store-table 을 true 로 켜면
--    Spring AI 가 vector_store 를 DROP 하여 여기서 만든 생성열·GIN 인덱스가 전부 사라진다.
--    현재 두 프로파일 모두 미설정(기본 false). 켜지 말 것.
--
-- ⚠ vector_store / knowledge_files 에서 수동 삭제를 했다면 반드시 아래를 실행할 것:
--       SELECT public.bm25_refresh_stats();
--    (앱에는 삭제 경로가 없어 자동 갱신이 걸리지 않는다.)
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. 토크나이저 — 문서 색인과 질의가 반드시 같은 함수를 쓴다
-- ---------------------------------------------------------------------
-- PostgreSQL 내장 FTS 는 한국어 사전이 없다. 'simple' 은 공백 단위로만 잘라
-- '테오리아로' 가 통째로 한 토큰이 되고, 질의 '테오리아' 는 0건이 된다(실측).
-- 문자 bigram 으로 쪼개면 '테오 오리 리아 아로' ⊃ '테오 오리 리아' 가 되어 매칭된다.
--
-- IMMUTABLE 근거: lower / regexp_split_to_table / substr / generate_series /
--   to_tsvector(regconfig, text) 는 모두 pg_proc 상 IMMUTABLE.
--   ★ 반드시 2-인자 to_tsvector('simple', ...) 를 쓸 것.
--     1-인자 to_tsvector(text) 는 default_text_search_config 의존이라 STABLE →
--     생성열에 쓸 수 없다.
--
-- WITH ORDINALITY + ORDER BY 로 position 을 결정적으로 만든다. BM25 는 position 을
-- 쓰지 않지만, 생성열 + GIN 인덱스를 받치는 IMMUTABLE 함수는 같은 입력에 대해
-- 바이트 단위로 같은 결과를 내야 한다.
--
-- 생성열 표현식에는 서브쿼리·집계를 직접 쓸 수 없다. 함수로 감싸는 것이 표준 우회법이며,
-- 본문에 FROM + 집계가 있어 PostgreSQL 이 인라인하지 않으므로 안정적이다.
CREATE OR REPLACE FUNCTION public.bm25_bigram_tsvector(txt text)
RETURNS tsvector
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT to_tsvector('simple', COALESCE(string_agg(s.bg, ' ' ORDER BY s.wn, s.i), ''))
    FROM (
        SELECT substr(w.word, i, 2) AS bg, w.wn, i
        FROM regexp_split_to_table(lower(COALESCE(txt, '')), '[^[:alnum:]가-힣]+')
                 WITH ORDINALITY AS w(word, wn),
             generate_series(1, greatest(length(w.word) - 1, 1)) AS i
        WHERE w.word <> ''
    ) s
$$;

COMMENT ON FUNCTION public.bm25_bigram_tsvector(text) IS
    '한국어용 문자 bigram 토크나이저. vector_store.content_bigram 생성열과 질의 양쪽에서 쓴다.';


-- 문서 길이(BM25 의 |D|) = tf 의 합.
--
-- ★ 원문에서 bigram 개수를 직접 세면 안 된다. tsvector 는 lexeme 당 position 을
--   최대 256개까지만 보관하고 BM25 의 tf 는 그 절단된 값을 쓴다. 길이도 같은 절단을
--   통과해야 길이 정규화(b 항)가 일관된다.
--
-- ★ 별도 함수가 필수다. 생성열은 다른 생성열을 참조할 수 없으므로 content_bigram 을
--   읽을 수 없고, content 에서 tsvector 를 다시 만들어야 한다(INSERT 시점 한정 비용).
CREATE OR REPLACE FUNCTION public.bm25_bigram_len(txt text)
RETURNS integer
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(SUM(COALESCE(array_length(u.positions, 1), 0)), 0)::integer
    FROM unnest(public.bm25_bigram_tsvector(txt)) AS u
$$;

COMMENT ON FUNCTION public.bm25_bigram_len(text) IS
    'BM25 길이 정규화용 문서 길이. tf 와 같은 256 position 절단을 통과시킨다.';


-- ---------------------------------------------------------------------
-- 2. vector_store 생성열 — Spring AI 가 모르는 채로 자동 유지된다
-- ---------------------------------------------------------------------
-- PgVectorStore 2.0.0-M4 확인 사항:
--   * INSERT 는 (id, content, metadata, embedding) 컬럼을 명시 → 생성열 침범 없음
--   * ON CONFLICT DO UPDATE SET content=... → 생성열 자동 재계산
--   * 검색은 SELECT * 지만 DocumentRowMapper 가 이름으로 4개만 읽음 → 추가 컬럼 무시
--   * 부팅 시 CREATE TABLE IF NOT EXISTS → 이미 있는 테이블에 no-op
-- 따라서 색인 경로에 애플리케이션 코드가 전혀 필요 없다.
ALTER TABLE public.vector_store
    ADD COLUMN IF NOT EXISTS content_bigram tsvector
        GENERATED ALWAYS AS (public.bm25_bigram_tsvector(content)) STORED;

ALTER TABLE public.vector_store
    ADD COLUMN IF NOT EXISTS bigram_len integer
        GENERATED ALWAYS AS (public.bm25_bigram_len(content)) STORED;


-- ---------------------------------------------------------------------
-- 3. 인덱스
-- ---------------------------------------------------------------------
-- 참고: 2026-09-17 기준 318행에서는 플래너가 seq scan 을 고르므로 아래 인덱스들은
--       사실상 no-op 이다(HNSW 가 안 쓰이는 것과 같은 이유 — measurements.md 참조).
--       코퍼스가 커지면 그때부터 효과가 난다. "안 쓰이니 지우자" 는 오판이다.

-- BM25 후보 축소용
CREATE INDEX IF NOT EXISTS idx_vector_store_content_bigram
    ON public.vector_store USING GIN (content_bigram);

-- 소유권 조인(metadata->>'parent_document_id' = knowledge_files.file_id)용 표현식 인덱스.
-- 기존 KnowledgeFileMapper.selectKnowledgeChunkList 도 같이 빨라진다.
CREATE INDEX IF NOT EXISTS idx_vector_store_parent_document_id
    ON public.vector_store ((metadata ->> 'parent_document_id'));

-- 소유자 파일 목록 조회용
CREATE INDEX IF NOT EXISTS idx_knowledge_files_owner
    ON public.knowledge_files (create_user_id, category_id);


-- ---------------------------------------------------------------------
-- 4. BM25 통계 스냅샷
-- ---------------------------------------------------------------------
-- 전역 문서빈도(df). ts_stat 결과의 스냅샷이다.
CREATE TABLE IF NOT EXISTS public.vector_store_bm25_term_stats (
    lexeme  text    NOT NULL,                                   -- bigram 1개
    df      integer NOT NULL,                                   -- 이 bigram 을 포함한 청크 수 (ts_stat.ndoc)
    CONSTRAINT pk_vector_store_bm25_term_stats PRIMARY KEY (lexeme)
);

-- N(문서 수) / avgdl(평균 길이). 항상 1행('GLOBAL').
--
-- ★ 왜 저장하나: 속도가 아니라 정합성 때문이다.
--   IDF = ln(1 + (N - df + 0.5)/(df + 0.5)) 인데 N 이 실시간이고 df 가 스냅샷이면
--   둘이 어긋난다. 삭제가 누적되면 N < df 가 되어 IDF 가 음수까지 간다.
--   df 와 같은 트랜잭션에서 갱신하면 구조적으로 불가능해진다.
CREATE TABLE IF NOT EXISTS public.vector_store_bm25_corpus_stats (
    stats_key    varchar(20) NOT NULL,                          -- 고정값 'GLOBAL'
    doc_count    bigint      NOT NULL,                          -- N
    avg_doc_len  numeric     NOT NULL,                          -- avgdl
    update_date  timestamp   DEFAULT NOW(),                     -- 갱신 시각
    CONSTRAINT pk_vector_store_bm25_corpus_stats PRIMARY KEY (stats_key)
);


-- ---------------------------------------------------------------------
-- 5. 통계 갱신 — DELETE + 재삽입을 한 트랜잭션으로
-- ---------------------------------------------------------------------
-- MVCC 덕분에 동시 조회는 커밋 전까지 이전 스냅샷을 온전히 본다.
-- 즉 원자적 교체가 공짜이며, 조회가 빈 통계 테이블에 착지할 위험이 없다.
--
-- 증분 갱신을 하지 않는 이유: ts_stat 은 어차피 전체 tsvector 를 훑는다.
-- 부분 갱신은 코드만 복잡해지고 df 가 표류할 수 있다. 전량 재계산이 가장 안전하다.
--
-- 반환값 = 기록된 term 수 (호출부 로그용).
CREATE OR REPLACE FUNCTION public.bm25_refresh_stats()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    term_count integer;
BEGIN
    DELETE FROM public.vector_store_bm25_term_stats;

    INSERT INTO public.vector_store_bm25_term_stats (lexeme, df)
    SELECT word, ndoc
    FROM ts_stat('SELECT content_bigram FROM public.vector_store WHERE content_bigram IS NOT NULL');

    GET DIAGNOSTICS term_count = ROW_COUNT;

    INSERT INTO public.vector_store_bm25_corpus_stats (stats_key, doc_count, avg_doc_len, update_date)
    SELECT 'GLOBAL', COUNT(*), COALESCE(AVG(NULLIF(bigram_len, 0)), 1), NOW()
    FROM public.vector_store
    WHERE content_bigram IS NOT NULL
    ON CONFLICT (stats_key) DO UPDATE
        SET doc_count   = EXCLUDED.doc_count,
            avg_doc_len = EXCLUDED.avg_doc_len,
            update_date = EXCLUDED.update_date;

    RETURN term_count;
END;
$$;

COMMENT ON FUNCTION public.bm25_refresh_stats() IS
    'BM25 df/N/avgdl 스냅샷 전량 재계산. KnowledgeFileService.upload 가 업로드 직후 호출한다.';


-- ---------------------------------------------------------------------
-- 6. 부트스트랩 — 이 파일의 마지막 문장이어야 한다
-- ---------------------------------------------------------------------
SELECT public.bm25_refresh_stats() AS indexed_term_count;
