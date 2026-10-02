---
name: mybatis-pgvector-data
description: MyBatis 4.0.1 + PostgreSQL + pgvector 데이터 계층 코드를 작성할 때 사용. Mapper interface, namespace 일치 XML, 명시적 ResultMap, pgvector 컬럼 vector(n) DDL, hnsw/ivfflat 인덱스, Spring AI 기본 vector_store 테이블과 충돌 없는 도메인 테이블 설계, #{} 안전 바인딩을 보장. mybatis-data-engineer 에이전트 전용. "MyBatis Mapper 생성", "ResultMap 매핑", "pgvector 인덱스", "DDL 마이그레이션" 요청 시 반드시 트리거.
---

## 사용 시점

mybatis-data-engineer 에이전트가 MyBatis Mapper/XML 또는 PostgreSQL DDL을 작성할 때.

## 디렉토리 구조

```
src/main/java/kr/co/jihun/guisample/mapper/
  └── *Mapper.java                   (interface, @Mapper)
src/main/resources/mapper/
  └── *Mapper.xml                    (namespace = interface FQN)
src/main/resources/db/migration/
  └── V{n}__{description}.sql        (DDL/마이그레이션, 멱등성 유지)
```

`mapper.xml` 위치는 application.yaml의 `mybatis.mapper-locations`와 정합. 미지정 시 기본 `classpath*:mapper/**/*.xml`를 따른다 — 변경 필요 시 application.yaml에 명시.

## 코드 패턴

### Mapper Interface

```java
package kr.co.jihun.guisample.mapper;

@Mapper
public interface RagChunkMapper {
    int insertChunk(@Param("chunk") RagChunkDTO chunk);
    List<RagChunkDTO> findBySourceId(@Param("sourceId") String sourceId);
}
```

원칙:
- 모든 파라미터에 `@Param` 명시 (단일 객체 포함). XML에서 `#{chunk.field}` 형태로 명확히
- 메서드명은 동사로 시작 (insert/select/find/update/delete)

### Mapper XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="kr.co.jihun.guisample.mapper.RagChunkMapper">

  <resultMap id="ragChunkMap" type="kr.co.jihun.guisample.vo.RagChunkDTO">
    <id column="id" property="id"/>
    <result column="source_id" property="sourceId"/>
    <result column="chunk_index" property="chunkIndex"/>
    <result column="content" property="content"/>
    <result column="metadata" property="metadata" typeHandler="..."/>
    <result column="created_at" property="createdAt"/>
  </resultMap>

  <insert id="insertChunk">
    INSERT INTO rag_chunks (source_id, chunk_index, content, embedding, metadata)
    VALUES (#{chunk.sourceId}, #{chunk.chunkIndex}, #{chunk.content},
            #{chunk.embedding}::vector, #{chunk.metadata}::jsonb)
  </insert>

  <select id="findBySourceId" resultMap="ragChunkMap">
    SELECT id, source_id, chunk_index, content, metadata, created_at
    FROM rag_chunks
    WHERE source_id = #{sourceId}
    ORDER BY chunk_index
  </select>
</mapper>
```

원칙:
- `namespace`는 interface FQN과 **정확히** 일치
- `<resultMap>`을 명시. auto-mapping 의존 금지
- `#{}` 사용. `${}` 금지 (동적 정렬은 화이트리스트 후 별도 처리)
- vector/jsonb 컬럼은 `::vector`, `::jsonb` 캐스팅

## DDL 패턴

```sql
-- V1__create_rag_chunks.sql

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS rag_chunks (
  id          BIGSERIAL PRIMARY KEY,
  source_id   VARCHAR(64) NOT NULL,
  chunk_index INT         NOT NULL,
  content     TEXT        NOT NULL,
  embedding   vector(1536) NOT NULL,
  metadata    JSONB,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_source
  ON rag_chunks(source_id);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_embed_hnsw
  ON rag_chunks USING hnsw (embedding vector_cosine_ops);
```

원칙:
- 멱등: `IF NOT EXISTS`
- 벡터 차원은 임베딩 모델과 일치 (text-embedding-3-small=1536)
- 인덱스 metric은 검색 시 metric과 같아야 함 (`vector_cosine_ops` ↔ `<=>` cosine)

## Spring AI VectorStore 테이블과의 관계

`application.yaml`에 `spring.ai.vectorstore.pgvector.initialize-schema: true`가 있으면 spring-ai-starter가 `vector_store` 테이블을 자동 생성한다. 결정:

| 시나리오 | 권장 |
|---------|-----|
| Spring AI 기본 테이블만 사용 (단일 컬렉션) | 별도 DDL 불필요. `VectorStore` 빈 그대로 사용 |
| 도메인 메타데이터가 풍부 / 여러 컬렉션 / 별도 비즈니스 쿼리 필요 | 별도 테이블 생성. `vector_store`와 이름 충돌 없게 |

별도 테이블을 만들 때 `_workspace/02_data_summary.md`에 "왜 별도 테이블인가" 근거 1줄 이상.

## 작업 순서

1. `_workspace/00_architect_design.md`의 데이터 모델 섹션 정독
2. spring-backend-engineer로부터 Mapper 시그니처 요구 메시지 확인
3. DTO 합의 (vo/ 에 이미 있거나 신규 — 백엔드와 충돌 시 협의)
4. DDL 작성 → Mapper interface → Mapper XML 순
5. 부팅 시 BindingException이 나면 namespace/메서드 ID 검증
6. `_workspace/02_data_summary.md` 작성 (Mapper 목록, ResultMap 매핑 표, DDL 요약)
7. spring-backend-engineer에 `SendMessage`로 시그니처 확정 통지

## 흔한 함정

| 함정 | 해결 |
|------|-----|
| namespace 오타 / interface FQN 불일치 | 빌드 시점에 `BindingException`. 즉시 namespace 확인 |
| ResultMap의 property가 DTO에 없음 | DTO 필드명 확인. 오타 수정 |
| vector 컬럼에 List<Float> 직접 바인딩 | typeHandler 또는 `::vector` 캐스팅 + 문자열 전달 (`[0.1,0.2,...]`) |
| `${}` 사용 | 화이트리스트 후 `<choose>`로 분기 |
| Spring AI `vector_store`와 컬럼명 충돌 | 별도 테이블 이름 변경 (`rag_chunks` 등 도메인 prefix) |

## 후속 작업

`_workspace/02_data_summary.md`가 있으면 먼저 읽는다. 스키마 변경이 기존 데이터에 파괴적이면 사용자 승인 받기 전 DDL 실행 금지.
