# 0005. 하이브리드 검색: BM25 키워드 + dense 벡터 RRF 융합

- 상태(Status): 승인됨(Accepted)
- 날짜(Date): 2026-09-17

## 배경(Context)

dense 벡터 검색 단독으로는 고유명사를 찾지 못한다. 같은 날 임베딩 모델을 `text-embedding-3-small` → `text-embedding-3-large@1536` 으로 올려 문서형 질의는 크게 개선됐지만(최고 유사도 0.477 → 0.667), 고유명사 질의는 그대로였다.

318 청크 실측:

| 질의 | dense 최고 유사도 | 판정 |
|------|------------------|------|
| "pgvector 도커 실행 방법" | 0.667 | 정답 |
| "테오리아 행성은 어디에 있나" | 0.372 | 정답 청크는 코퍼스에 있으나 노이즈와 구분 불가 |
| "오늘 점심 메뉴 추천해줘" | 0.247 | 무관(노이즈 상한) |

"테오리아" 는 코퍼스에 실제로 존재하는 고유명사인데 0.372 로, 노이즈 상한 0.247 과 의미 있게 분리되지 않는다. dense 임베딩의 구조적 약점이라 모델 교체로는 해결되지 않는다.

## 결정(Decision)

BM25 키워드 검색을 추가하고 dense 결과와 **RRF(Reciprocal Rank Fusion)** 로 융합한다. 검색 대상은 dense·keyword 양쪽 모두 `knowledge_files.create_user_id` 로 제한한다.

### 1. 키워드 백엔드 — PostgreSQL 내장 FTS + 문자 bigram

PostgreSQL 내장 FTS 에는 **한국어 사전이 없다.** `simple` 설정은 공백 단위로만 토큰을 자르므로 `'테오리아로'` 가 통째로 한 토큰이 되고, 질의 `'테오리아'` 는 **0건**이 된다(실측).

검토한 대안과 기각 사유:

| 후보 | 기각 사유 |
|------|----------|
| pg_bigm / pgroonga / ParadeDB pg_search | 현재 `pgvector/pgvector:pg17` 이미지에 없다. 이미지를 갈면 **같은 인스턴스의 Keycloak DB** 까지 위험해진다(`/Users/sunjihun/DockerFiles/docker-compose.yml` — Keycloak 이 `pgvector:5432/keycloak` 을 쓴다) |
| pg_trgm | 이미지에 있지만 BM25 가 아니다. trigram 중복률 기반이라 다중 키워드 질의의 랭킹이 약하다 |
| 앱 내 Lucene + Nori | 한국어 토큰화 품질은 최고지만 인덱스가 DB 밖 별도 상태가 되어 기동 시 재구축·다중 인스턴스 동기화 문제가 생긴다 |

**채택: 문자 bigram tsvector.** `'테오리아로'` → `'테오 오리 리아 아로'` 로 쪼개면 질의 `'테오리아'`(→ `'테오 오리 리아'`)가 매칭된다. 확장 설치도 이미지 교체도 필요 없다. pg_bigm 이 하는 일을 직접 구현하는 셈이다.

### 2. 랭킹 — `ts_rank_cd` 가 아니라 진짜 BM25

처음에는 `ts_rank_cd` 로 충분할 것으로 봤으나 **실측에서 무너졌다.** "테오리아 행성은 어디에 있나" 로 검색하면:

- AND 의미(`plainto_tsquery`): **0건**. 자연어 문장의 모든 bigram 을 포함하는 문서는 없다.
- OR 의미 + `ts_rank_cd`: 매칭은 되지만 **정답이 top-5 밖.** `'어디에'`·`'있나'` 같은 흔한 bigram 이 점수를 독식한다.

원인은 `ts_rank_cd` 에 **IDF 가 없다**는 것이다. BM25 의 IDF 항이 흔한 bigram 을 자동으로 억제한다. 같은 질의를 BM25 로 계산하니 정답 2건이 **#1(18.64)·#2(16.31)** 로 올라왔다.

따라서 df 통계 테이블을 두고 BM25 를 SQL 로 직접 계산한다:

```
BM25 = Σ ln(1 + (N - df + 0.5)/(df + 0.5)) · tf·(k1+1) / (tf + k1·(1 - b + b·len/avgdl))
k1 = 1.2, b = 0.75
```

### 3. 색인 유지 — STORED generated column

`vector_store` 는 Spring AI 소유 테이블이라 애플리케이션이 INSERT 를 제어하지 않는다. `content_bigram`/`bigram_len` 을 **STORED generated column** 으로 만들면 Spring AI 의 INSERT·UPSERT·psql 직접 UPDATE 전부에서 자동으로 맞는다. **색인 경로에 Java 코드가 0줄이다.**

Spring AI 2.0.0-M4 `PgVectorStore` 로 확인:
- INSERT 가 `(id, content, metadata, embedding)` 컬럼을 명시 → 생성열 침범 없음
- 검색이 `SELECT *` 지만 `DocumentRowMapper` 는 이름으로 4개만 읽음 → 추가 컬럼 무시
- 스키마 검증기는 필수 4개 컬럼의 **존재만** 확인 → 추가 컬럼 무관
- 부팅 시 `CREATE TABLE IF NOT EXISTS` → 기존 테이블에 no-op

### 4. 융합 — RRF

`score(d) = Σ 1/(k + rank_i(d))`, k=60. **순위만 사용**하므로 스케일이 전혀 다른 코사인 유사도(0~1)와 BM25 점수(0~수십)를 정규화 없이 합칠 수 있다. 융합 키는 `vector_store.id`(= Spring AI `Document.getId()` = `KeywordHitDTO.chunkId`).

### 5. 임계값 — 하나를 둘로 분리

기존 `similarityThreshold = 0.32` 는 **서로 다른 두 일**을 겸하고 있었다. 하이브리드에서는 분리해야 한다.

| 역할 | 값 | 적용 지점 |
|------|-----|----------|
| 후보 편입 | 0.25 | `SearchRequest.similarityThreshold`. 노이즈 상한 0.247 바로 위 |
| 답변 게이트(dense) | 0.32 | dense 최고 유사도 |
| 답변 게이트(keyword) | 2.0 | 정규화 BM25 최고점. dense 와 **OR** — 어느 한쪽이라도 확신하면 통과 |

게이트는 **융합 전에, 두 검색기 각각의 원 점수로** 판정한다. 융합 후 RRF 점수로 판정하면 순위 인공물이라 "관련 문서가 있었는가" 를 알 수 없고, dense 점수만으로 판정하면 키워드 쪽 승리를 스스로 버린다.

**게이트를 없애면 안 된다.** 문자 bigram 특성상 어떤 한국어 질의든 거의 모든 한국어 청크와 bigram 을 공유하므로, BM25 는 "오늘 점심 메뉴 추천해줘" 에도 후보를 가득 돌려준다. 융합 점수는 최대 `2/61 ≈ 0.033` 인 순위 인공물이라 "관련 문서가 있었는가" 를 담지 못하므로, 판정은 위 표대로 두 검색기의 원 점수로 한다. dense 게이트 값이 도입 전과 동일해 무관 질문의 기존 동작이 보존되고, keyword 게이트는 노이즈 상한(정규화 1.19) 위에 있어 무관 질문을 통과시키지 않는다.

### 6. 소유권 — 양쪽 모두 제한

기존 RAG 검색은 `category_id` 로만 필터해 **다른 사용자의 문서가 답변 근거로 들어올 수 있었다.** CLAUDE.md 의 "데이터 소유권 규칙"(소유자 컬럼이 없는 테이블은 상위 엔터티 소유권으로 막는다)을 RAG 검색만 지키지 않고 있었다.

- 키워드: SQL 에서 `knowledge_files` 조인 (`kf.file_id = vs.metadata->>'parent_document_id' AND kf.create_user_id = ?`)
- dense: 청크 metadata 에 사용자 키가 없으므로, 소유 `file_id` 목록을 먼저 뽑아 `in("parent_document_id", ids)` 메타데이터 필터로 **사전 필터**한다. 사후 필터는 남의 문서가 많을수록 조용히 손실되므로 기각했다.

`userName` 은 컨트롤러가 **요청 스레드에서** 꺼내 `category_id` 와 같은 경로(`advisors(spec -> spec.param(...))`)로 넘긴다 — `LoginUserSession` 은 요청 스레드 바인딩 프록시라 `boundedElastic` 에서 접근할 수 없다. 값이 없으면 전체 검색으로 폴백하지 않고(노출 방지) 컨텍스트를 비운다(SSE 경로라 예외도 던지지 않는다).

## 결과(Consequences)

### 좋은 점
- 고유명사 질의가 동작한다. "테오리아 행성은 어디에 있나" → BM25 #1·#2 가 정답 청크.
- 색인 유지에 애플리케이션 코드가 필요 없다(generated column).
- 확장 설치·이미지 교체가 없어 Keycloak DB 에 위험이 없다.
- 기존 교차 사용자 노출이 닫힌다.

### 감수하는 것
- **IDF 는 전역, 검색은 사용자별.** 엄밀히는 검색 대상 코퍼스로 IDF 를 계산해야 하지만, 사용자별 df 테이블은 `O(users × 17,835 terms)` 라 비현실적이다. 넓은 배경 코퍼스로 term 희귀도 prior 를 잡는 것은 IR 표준 관행이며, 같은 문서의 랭킹이 사용자와 무관하게 안정적이라는 장점도 있다. **측정 없이 "고치지" 말 것.**
- ~~dense 가 완전히 놓친 순수 lexical 질의는 게이트에서 걸러진다.~~ **2026-09-17 해결.** 실제로 "테오리아"(dense 0.20, BM25 15.83)가 이 문제로 실패하는 것을 확인하고 게이트를 OR 로 바꿨다. 키워드 쪽 하한은 **질의 bigram 수로 정규화한** BM25 ≥ 2.0 — 원점수는 질의 길이에 비례해 무관한 긴 질의(7.15)가 정답인 짧은 질의(2.84)를 이기므로 쓸 수 없다. 측정값은 measurements.md.
- **형태소 분석이 없다.** bigram 은 조사 문제를 우회할 뿐 어간을 알지 못한다. Nori/MeCab 수준의 정확도는 아니다.
- **규모 천장.** `unnest(content_bigram)` 는 후보마다 전체 tsvector 를 펼친다(현재 ~160k행, 밀리초). 선형 증가라 **5만 청크 근처에서 병목**이 된다. 그때의 탈출구: dense 검색도 MyBatis 로 내려 한 쿼리로 합치거나, 별도 역색인 테이블을 둔다.
- **인덱스는 당분간 no-op.** 318 청크에서는 플래너가 seq scan 을 고른다(HNSW 와 같은 이유). 안 쓰인다고 지우면 안 된다.
- `ADD COLUMN ... GENERATED` 는 테이블 전체 재작성이다. 코퍼스가 크면 잠금 시간을 감안할 것.
- 토크나이저 함수 정의를 바꿔도 **기존 생성열 값은 재계산되지 않는다.** 수정 시 컬럼 DROP 후 재생성.

## 운영 주의

- `src/main/resources/db/bm25_hybrid_schema.sql` 은 **수동 적용**이며 **코드보다 먼저** 적용해야 한다. 미적용 시 `HybridRetrievalService` 가 try/catch 로 dense-only 폴백하므로 치명상은 아니지만 키워드 검색이 조용히 빠진다.
- `spring.ai.vectorstore.pgvector.remove-existing-vector-store-table: true` 를 켜면 테이블이 DROP 되어 생성열·GIN 인덱스가 전부 사라진다. 현재 미설정(기본 false). 켜지 말 것.
- 앱에 삭제 경로가 없어 통계 갱신은 업로드 시에만 돈다. `vector_store`/`knowledge_files` 를 수동 삭제했다면 `SELECT public.bm25_refresh_stats();` 를 직접 실행할 것.

## 관련 문서

- `src/docs/measurements.md` — 실측 수치와 재측정 절차
- [0001. pgvector 채택](0001-use-pgvector.md), [0002. RAG 파이프라인·가드레일](0002-rag-guardrail-litellm.md)
