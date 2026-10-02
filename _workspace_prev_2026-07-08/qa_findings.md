# QA Findings — 지식파일 관리 확장 (2026-06-11)

> 검증: orchestrator(통합 정적 검증) | 이전 임베딩 검증 이력은 git/01·03 요약 참조

## 요구사항 매핑 (PASS)
| # | 요구사항 | 구현 위치 | 상태 |
|---|---------|----------|------|
| 1 | 우측 절반 "지식파일 목록" 그리드 | embedding.jsp #knowledgeGrid | ✅ |
| 2 | 초기 GET /user/knowledge/knowledges (jQuery AJAX) | jqGrid url+mtype:GET (내부 $.ajax) | ✅ |
| 3 | knowledge_files 테이블 조회 | KnowledgeFileMapper.xml | ✅ |
| 4 | 우상단 조회/등록 버튼 | #btnKnowledgeRead, #btnKnowledgeCreate | ✅ |
| 5 | 조회 클릭 → 목록 재조회 | reloadKnowledgeGrid() | ✅ |
| 6 | 등록 클릭 → 파일선택 레이어 팝업 | #knowledgePopup openKnowledgePopup() | ✅ |
| 7.1 | 업로드 POST /user/rag/embedding | AIRestController | ✅ |
| 7.2 | TokenTextSplitter 청킹 | EmbeddingService.embed | ✅ |
| 7.3 | 800토큰 | withChunkSize(800) | ✅ |
| 7.4 | vector_store | pgvector starter 자동관리 | ✅ |
| 7.5 | 청크 id = UUID | Document.builder 자동(청크별) | ✅ |
| 7.6 | content = 청크 | VectorStore.add | ✅ |
| 7.7 | metadata {"source":categoryId} | new Document(fileId,text,Map.of("source",categoryId)) | ✅ |
| 7.8 | embedding 컬럼 | VectorStore.add 내부 EmbeddingModel | ✅ |
| 7.9 | 파일 디스크 저장 | KnowledgeFileService.saveToDisk (UPLOAD_DIR) | ✅ |
| 7.10 | knowledge_files insert | insertKnowledgeFile | ✅ |
| 7.11 | file_id = UUID | UUID.randomUUID() | ✅ |
| 7.12 | vector_store file_id = file_id | metadata.parent_document_id = fileId | ✅ |
| 7.13 | file_id == 자동 parent_document_id | 부모 Document id=fileId → 자동 주입 | ✅ |

## 경계면 교차 검증 (PASS)
- multipart 필드 ↔ FormData: `file`,`categoryId` 일치
- 목록 Map shape ↔ jqGrid jsonReader: page/total/records/rows, id=fileId
- ResultMap column ↔ VO property: 8필드 일치
- SQL 컬럼 ↔ knowledge_files DDL: insert/select 일치, category_master 조인
- 업로드 응답 record ↔ JS: success/message 사용 일치
- Mapper namespace ↔ interface FQN + 3메서드

## 빌드
- `./gradlew clean compileJava` → BUILD SUCCESSFUL ✅

## 런타임 미검증 (제약)
- 라이브 PostgreSQL(pgvector) + OPENAI_API_KEY + knowledge_files 테이블 존재 필요.
- 권장 수동검증: ① 카테고리 행 선택 후 등록→파일 업로드→success ② vector_store에서
  `metadata->>'source'`=categoryId, `metadata->>'parent_document_id'`=knowledge_files.file_id 확인
  ③ 디스크 `/home/sunjihun/mydev/upload_files/knowledge_files/` 파일 생성 확인
  ④ 카테고리 미선택 시 등록 차단 alert.

## 비고
- common.css의 `.embed-card`, `.embed-card__actions`는 폼 제거로 미사용(고아) — 무해, 보존.

## 2026-06-12 카테고리 필터 검색 — 경계면 검증

- [PASS] JS 요청 body `{query, categoryId}` ↔ `AIRestController.getDocs` `body.get("query"/"categoryId")` 일치. categoryId 생략 시 null → 필터 미적용.
- [PASS] 필터 키 `category_id` ↔ 임베딩 적재 키 일치 (`EmbeddingService.java:51` parent Document metadata `Map.of("category_id", categoryId)`, TokenTextSplitter가 청크에 복사).
- [PASS] 카테고리 목록 응답 shape `{page,total,records,rows:[{categoryId,categoryName}]}` ↔ JS 파서(`data.rows` 배열, `categoryId`/`categoryName` 필드) 일치 (`CategoryMasterRestController.categoryList`, `CategoryMasterVO`).
- [PASS] `./gradlew compileJava` 통과 — Spring AI 2.0.0-M4 `FilterExpressionBuilder`/`SearchRequest.Builder.filterExpression` 시그니처 검증.
- 비고: 본 사이클은 서브 에이전트 Edit 권한 거부로 오케스트레이터가 에이전트 준비안을 직접 적용. 검증도 오케스트레이터 인라인 수행.
