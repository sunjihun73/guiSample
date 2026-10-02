# 02. 데이터 구현 요약 — knowledge_files

> 작성: 데이터 계층 | 날짜: 2026-06-11

## 생성 파일
| 구분 | 경로 |
|------|------|
| 신규 | `vo/KnowledgeFileVO.java` |
| 신규 | `mapper/KnowledgeFileMapper.java` |
| 신규 | `resources/mapper/KnowledgeFileMapper.xml` |

> **테이블 생성 안 함** (사용자 지시). `knowledge_files` 기존 존재 가정. DDL 파일/실행 모두 없음.

## VO ↔ ResultMap ↔ 컬럼
| VO property | column | 비고 |
|-------------|--------|------|
| fileId | file_id | `<id>` |
| categoryId | category_id | |
| fileName | file_name | |
| updateUserId | update_user_id | |
| updateDate | update_date | |
| createUserId | create_user_id | |
| createDate | create_date | |
| categoryName | category_name | category_master LEFT JOIN (테이블 컬럼 아님, 표시용) |

## Mapper 메서드 (namespace = interface FQN)
- `selectKnowledgeFileList(HashMap)` — LIMIT/OFFSET 페이지네이션, create_date DESC, category_master 조인
- `countKnowledgeFile(HashMap)`
- `insertKnowledgeFile(KnowledgeFileVO)` — 날짜 NOW(), file_id/category_id/file_name 명시

## mybatis 설정
- mapper-locations: `classpath:mapper/**/*.xml` (DataSourceConfig) → 신규 XML 자동 등록
- mapUnderscoreToCamelCase=true (그러나 명시 resultMap 사용)

## 후속 추가 (2026-06-15) — 청크 목록 조회
- VO 신규: `vo/KnowledgeChunkVO.java` (chunkId/parentDocumentId/categoryId/chunkIndex/totalChunks/content)
- Mapper: `selectKnowledgeChunkList(HashMap)` 추가 + `knowledgeChunkResultMap`
- SQL: `vector_store` 에서 `metadata ->> 'parent_document_id' = #{parentDocumentId}`, `ORDER BY (metadata ->> 'chunk_index')::int ASC`
- metadata(json) 키 = category_id / chunk_index / total_chunks / parent_document_id (실 DB 확인)
