---
name: mybatis-pgvector-data
description: guiSample 의 MyBatis(mybatis-spring-boot-starter 4.0.1) + PostgreSQL(pgvector) 데이터 계층을 작성하는 방법. namespace 일치 interface/XML 쌍, 명시적 ResultMap, <sql> 조각 재사용, 소유권 조건, jqGrid 페이징(pageSize/startRow), 매퍼 주석 내 #{} 금지, src/main/resources/db/*.sql 수동 적용 DDL, Spring AI vector_store·BM25 스키마와의 관계를 다룬다. mybatis-data-engineer 가 사용한다. "매퍼 추가", "쿼리 수정", "ResultMap", "DDL 작성", "컬럼 추가", "pgvector 인덱스" 요청 시 트리거.
---

## 사용 시점

mybatis-data-engineer 가 매퍼 또는 DDL 을 작성할 때.

## 구조 (실제)

```
src/main/java/kr/co/jihun/guisample/mapper/*Mapper.java    (@Mapper interface)
src/main/resources/mapper/*Mapper.xml                      (namespace = interface FQN)
src/main/resources/db/*.sql                                (수동 적용 DDL — 부팅 시 실행 안 됨)
```

`SqlSessionFactory` 는 `DataSourceConfig` 가 직접 구성한다(자동구성 꺼짐): `mapUnderscoreToCamelCase=true`, `typeAliasesPackage=...dto`, `mapperLocations=classpath:mapper/**/*.xml`. 설정 위치를 application yaml 에서 찾지 않는다.

## 코드 패턴 (`KnowledgeFileMapper` 표준형)

### interface

```java
@Mapper
public interface ItemMapper
{
    List<ItemDTO> selectItemList(HashMap<String, Object> param);

    int countItem(HashMap<String, Object> param);

    int insertItem(ItemDTO item);
}
```

- 조회 조건은 `HashMap<String,Object>` 하나, 쓰기는 DTO 하나 — 기존 매퍼와 같은 형태. 여러 스칼라가 필요할 때만 `@Param`
- Javadoc 에 필수/선택 파라미터 키를 적는다(`@param param createUserId(필수), categoryId(선택)`)

### XML

```xml
<mapper namespace="kr.co.jihun.guisample.mapper.ItemMapper">

    <resultMap id="itemResultMap" type="kr.co.jihun.guisample.dto.ItemDTO">
        <id     property="itemId"       column="item_id"/>
        <result property="createUserId" column="create_user_id"/>
    </resultMap>

    <sql id="itemWhere">
        <where>
            <if test="itemName != null and itemName != ''">
                AND i.item_name LIKE CONCAT('%', #{itemName}, '%')
            </if>
            <!-- 로그인 사용자 필터: 컨트롤러가 세션의 user_name 을 createUserId 로 넣는다 -->
            <if test="createUserId != null and createUserId != ''">
                AND i.create_user_id = #{createUserId}
            </if>
        </where>
    </sql>

    <select id="selectItemList" parameterType="hashmap" resultMap="itemResultMap">
        SELECT i.item_id, i.create_user_id
        FROM item_master i
        <include refid="itemWhere"/>
        ORDER BY i.create_date DESC
        <if test="pageSize != null">
            LIMIT #{pageSize} OFFSET #{startRow}
        </if>
    </select>

    <select id="countItem" parameterType="hashmap" resultType="int">
        SELECT COUNT(*) FROM item_master i
        <include refid="itemWhere"/>
    </select>
</mapper>
```

원칙:
- 목록과 count 가 **같은 `<sql>` 조각**을 include — 총건수와 목록의 조건이 어긋나지 않게
- 페이징은 `pageSize != null` 일 때만 LIMIT/OFFSET(컨트롤러가 count 뒤에 넣는다)
- ResultMap 명시. `mapUnderscoreToCamelCase` 가 켜져 있어도 auto-mapping 에 기대지 않는다
- 위 예시 주석처럼 **주석에는 `#{}` 표기를 절대 쓰지 않는다**

### 매퍼 주석 속 `#{}` — 왜 금지인가

MyBatis 의 파라미터 치환은 SQL 주석(`--`, `/* */`, `<!-- -->` 로 감싼 SQL 내부 포함)을 구분하지 않는다. 주석 속 예시도 바인딩으로 잡혀 MyBatis 는 파라미터 n+1개, 드라이버는 `?` n개를 보게 되고 `The column index is out of range` 로 실패한다. psql 에 SQL 을 직접 돌려서는 절대 재현되지 않으므로 **MyBatis 를 통과시켜** 확인해야 한다.

## 소유권

- 소유자 컬럼이 있으면 WHERE 조각에 조건을 둔다(`chat_owner_user_id`, `create_user_id`)
- 소유자 컬럼이 없는 자식 데이터는 상위 엔터티 소유권 확인용 쿼리를 따로 둔다(예: `selectOwnedFileIdList` → `parent_document_id IN (...)`)
- 소유권 예외(REPO동기화의 전체 대상 조회처럼)는 설계 문서에 근거가 있을 때만, XML 주석에 "의도된 예외" 와 ADR 번호를 적는다

## DDL

- 파일: `src/main/resources/db/{기능}_schema.sql`. 멱등(`IF NOT EXISTS`), 상단 주석에 **적용 대상 DB**(앱 5432)와 적용 순서
- 도메인 테이블은 앱 DB. 워크플로우 DB(5433)의 ACT_*/FLW_* 는 Flowable 이 만든다 — DDL 을 쓰지 않는다
- 실제 적용은 하지 않는다(사용자 승인 필요). 코드보다 먼저 적용해야 하면 요약에 명시

## pgvector · 키워드 검색

- `vector_store` 는 Spring AI 가 자동 생성(`initialize-schema: true`, 1536, HNSW, cosine). 소유자 컬럼이 없고, 청크 metadata `parent_document_id` = `knowledge_files.file_id` 로 소유권을 잇는다
- BM25 키워드 검색 스키마는 `db/bm25_hybrid_schema.sql`(수동, 코드보다 먼저). 미적용 시 dense-only 로 조용히 폴백 — 키워드 쿼리를 바꾸면 이 스키마와 `KeywordSearchMapper.xml` 을 함께 본다
- 별도 벡터 테이블은 설계 문서에 근거가 있을 때만. 차원은 임베딩 모델과 일치, 인덱스 ops 는 검색 metric 과 일치

## 작업 순서

1. 설계 문서 §2·§3 정독, 같은 도메인의 기존 매퍼를 읽는다
2. DDL 파일 → interface → XML
3. 시그니처 확정 즉시 `backend` 에 `SendMessage`(메서드, 파라미터 키, 반환 타입)
4. 자체 검증(namespace, ResultMap property, 주석 속 `#{`) 후 `_workspace/02_data_summary.md`
5. `qa` 에 검증 요청

## 흔한 함정

| 함정 | 해결 |
|------|------|
| 주석에 `#{fileId}` 같은 예시 | 주석에서 `#{}` 표기 제거. MyBatis 통과로 확인 |
| ResultMap type 이 `vo.` 패키지 | `kr.co.jihun.guisample.dto.*` |
| 목록에만 조건 추가, count 누락 | `<sql>` 조각 공유 |
| DDL 을 `db/migration/V*.sql` 로 | 이 프로젝트는 Flyway 가 없다. `db/*.sql` 수동 적용 |
| namespace 오타 | 부팅 시 `BindingException` — FQN 확인 |
| `${}` 로 정렬 | 화이트리스트 후 `<choose>` |

## 후속 작업

`_workspace/02_data_summary.md` 를 먼저 읽는다. 기존 데이터에 파괴적인 변경은 승인 전 DDL 적용 금지.
