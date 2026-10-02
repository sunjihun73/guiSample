---
name: spring-ai-rag-design
description: Spring AI 1.x/2.x M-line + PostgreSQL pgvector 기반 RAG 기능을 설계할 때 사용. 벡터 테이블 스키마, 임베딩 모델 선택, 청킹 전략(크기/오버랩), top-k와 유사도 metric, QuestionAnswerAdvisor 등 ChatClient Advisor 구성, 시스템 프롬프트, REST API 시그니처, JSP 화면 시나리오까지의 전 RAG 파이프라인 설계 결정을 일관되게 문서화. rag-architect 에이전트가 사용한다. "RAG 설계", "벡터 스키마", "임베딩 전략", "프롬프트 설계", "Spring AI 파이프라인" 요청 시 반드시 트리거.
---

## 사용 시점

rag-architect 에이전트가 Spring AI/RAG 기능의 설계 결정 문서를 작성할 때.
**코드를 직접 만들지 않는다.** 이 스킬은 설계 단계의 의사결정 가이드다.

## 출력 산출물

`_workspace/00_architect_design.md` — 다음 8개 섹션 모두 포함.

## 8개 섹션 작성 가이드

### 1. 목표와 범위
- 사용자 시나리오 1~3개. "어떤 입력으로 어떤 결과를 보여주는가."
- 비범위(non-goals)도 1~2개 명시. 무한 확장 방지.

### 2. 데이터 모델

**테이블 설계 결정:**
- Spring AI 기본 테이블(`vector_store`)을 그대로 쓸 것인가, 도메인용 별도 테이블을 만들 것인가
  - 기본 테이블: PoC, 단일 컬렉션
  - 별도 테이블: 다중 컬렉션, 도메인 메타데이터가 풍부할 때
- pgvector 컬럼: `vector(1536)` (text-embedding-3-small 기준), `vector(3072)` (text-embedding-3-large 기준)
- 인덱스: 데이터 < 10K → IVFFlat, 데이터 > 100K → HNSW. 그 사이는 양쪽 모두 가능
- 메타데이터: `jsonb` 컬럼 권장. 자주 필터링하는 필드는 별도 컬럼으로 승격

**스키마 예시 (참고):**
```sql
CREATE TABLE rag_chunks (
  id BIGSERIAL PRIMARY KEY,
  source_id VARCHAR(64) NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  embedding vector(1536) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX ON rag_chunks USING hnsw (embedding vector_cosine_ops);
```

### 3. 인덱싱 파이프라인

**청킹 결정:**
- 단위: 문자, 토큰, 문장, 문단 — 도메인 데이터의 자연 단위를 따른다
- 크기: 일반 텍스트 500~1000 토큰, 코드/JSON은 더 작게
- 오버랩: 청크 크기의 10~20%
- Spring AI `TokenTextSplitter`, `TextReader` 등 활용 명시

**임베딩 모델 결정:**
- text-embedding-3-small: 비용 효율, 1536차원, 일반 용도
- text-embedding-3-large: 정확도 우선, 3072차원
- 모델 변경 시 기존 벡터 전체 재생성 필요 → 마이그레이션 계획 함께 기재

**배치 vs 스트리밍:**
- 초기 일괄 적재: 배치 (트랜잭션 분할)
- 사용자 업로드: 동기 또는 비동기 처리 결정

### 4. 검색 파이프라인

- top-k: 3~10. 작을수록 정확, 클수록 회상
- 유사도 metric: cosine(기본), euclidean, dot_product 중 인덱스와 일치하게
- 메타데이터 필터: `SearchRequest.builder().filterExpression(...)`
- 재순위(rerank): 기본은 미사용. 정확도가 부족할 때만 cross-encoder 도입 검토 (의존성 추가 필요)

### 5. 프롬프트/Advisor 구성

- `ChatClient.builder(chatModel).build()`를 빈으로 등록
- RAG에는 `QuestionAnswerAdvisor.builder(vectorStore).searchRequest(...).build()`
- 대화 메모리가 필요하면 `MessageChatMemoryAdvisor`도 추가
- 시스템 프롬프트는 다음 4요소: 역할 / 컨텍스트 사용 규칙 / 모르는 것은 모른다고 답할 것 / 출력 형식

### 6. API 스펙

REST 엔드포인트 표 작성:

| Method | Path | 요청 DTO | 응답 DTO | 설명 |
|--------|------|---------|---------|------|
| POST | /api/rag/ingest | IngestRequest | IngestResponse | 문서 인덱싱 |
| POST | /api/rag/query | QueryRequest | QueryResponse | RAG 질의 |

DTO 필드를 빠짐없이 명시. JSP/JS가 그대로 파싱할 수 있게.

### 7. UI 시나리오

- 화면 라우트 (예: `GET /user/rag` → `WEB-INF/views/user/rag.jsp`)
- 사용자 인터랙션 흐름 (입력 → 호출 → 표시)
- 표시할 응답 필드와 형식 (출처 표시 여부 등)

### 8. 분배 지시

각 팀원에게 무엇을 맡길지 명시:
- **spring-backend-engineer**: 엔드포인트 X·Y, ChatClient/VectorStore 설정, IngestService 등
- **mybatis-data-engineer**: 테이블 DDL, Mapper interface(필요 시), ResultMap
- **jsp-frontend-engineer**: rag.jsp, rag.js, rag.css

## 작성 원칙

- 결정에는 **근거**를 1줄 이상 적는다. "왜 hnsw인가", "왜 청크 800자인가"
- 확실하지 않은 부분은 **"확인 필요"** 마크. 거짓 결정보다 명시적 미정이 낫다
- 기존 application.yaml과 build.gradle을 먼저 읽고, 의존성 추가가 필요하면 별도 섹션으로 기록

## 후속 작업

기존 `00_architect_design.md`가 있으면 먼저 Read한다. 사용자 피드백이 특정 섹션에 한정되면 그 섹션만 수정하고 변경 이력을 문서 상단에 추가.
