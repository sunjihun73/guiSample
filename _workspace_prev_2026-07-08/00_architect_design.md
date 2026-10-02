# 00. RAG 인덱싱 설계 — 파일 업로드 → 청킹 → 임베딩 → pgvector 저장

> 작성: rag-architect | 날짜: 2026-06-05
> 대상 스택: Spring Boot 4.0.6 / Spring AI BOM 2.0.0-M4 / pgvector / MyBatis / JSP(JSTL)
> 패키지 루트: `kr.co.jihun.guisample`

## 변경 이력
| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-06-05 | 초기 작성 (파일 업로드 인덱싱 파이프라인) | 신규 기능 |

---

## 1. 목표와 범위

### 사용자 시나리오
- 사용자가 `/user/embedding` 화면에서 **텍스트 파일을 선택**하고 **source 값(문서 출처 식별자)을 입력**한 뒤 업로드 버튼을 누른다.
- 백엔드는 파일 본문을 추출 → 800토큰 단위 청킹 → 각 청크를 임베딩 → `vector_store`에 저장한다.
- 화면에는 처리 결과(성공 여부, source, 생성된 청크 수, 메시지)가 표시된다.
- 이렇게 적재된 청크는 기존 `/user/rag` 챗봇(AIService.getDocs)의 검색 컨텍스트로 즉시 활용된다.

### 비범위 (Non-goals)
- **PDF/DOCX 등 바이너리 포맷 파싱은 이번 범위에서 제외.** plain text(`.txt`, `.md` 등 UTF-8 텍스트) 만 지원. (근거: 현재 의존성에 spring-ai-tika-document-reader / Apache PDFBox 가 없음. 바이너리 지원 시 의존성 추가 필요 — 9절 참조.)
- 업로드 비동기 처리/진행률 스트리밍 제외 (동기 처리, 단일 응답).
- 중복 적재 방지(dedup)/재인덱싱/삭제 API 제외.

---

## 2. 데이터 모델

### 테이블: `vector_store` (Spring AI starter 자동 생성·관리)
`spring.ai.vectorstore.pgvector.initialize-schema: true` 이므로 애플리케이션 기동 시 starter가 다음 스키마를 생성/검증한다. **별도 DDL/Flyway/MyBatis Mapper 불필요.**

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | `uuid` | 청크 고유 ID. Document 생성 시 자동 부여되는 UUID (요구사항 6 자동 충족) |
| `content` | `text` | 청크 본문 (요구사항 7 자동 충족) |
| `metadata` | `json` (jsonb) | `{"source":"<화면 입력값>"}` (요구사항 8) |
| `embedding` | `vector(1536)` | text-embedding-3-small 임베딩 (요구사항 9, dimensions 1536) |

- **인덱스:** HNSW + cosine (`vector_cosine_ops`). application.yaml의 `index-type: HNSW`, `distance-type: COSINE_DISTANCE`와 일치. starter가 생성.
- **근거(HNSW):** application.yaml에 이미 확정되어 있으며 검색 품질·확장성에서 무난. 현 데이터량과 무관하게 기설정을 따른다.

### 메타데이터 필드
- `source` (String): 화면에서 입력한 출처 식별자. 향후 메타데이터 필터(`SearchRequest.filterExpression`)의 키로 활용 가능. 이번 버전에서는 검색 필터에 사용하지 않고 저장만 한다.

---

## 3. 인덱싱 파이프라인

### 전체 흐름
```
[JSP multipart form: file + source]
   │  POST /user/rag/embedding  (multipart/form-data)
   ▼
[AIRestController.embedding(MultipartFile file, String source)]
   ▼
[EmbeddingService.ingest(file, source)]
   1) 파일 본문 텍스트 추출  : new String(file.getBytes(), StandardCharsets.UTF_8)
   2) 원본 Document 1건 생성  : new Document(text, Map.of("source", source))
   3) 800토큰 청킹           : TokenTextSplitter(chunkSize=800).apply(List.of(doc))
                              → 각 청크 Document는 부모 metadata(source) 를 상속
   4) 벡터 저장              : vectorStore.add(chunkDocuments)
                              → EmbeddingModel(text-embedding-3-small) 호출 후
                                id(UUID)+content+metadata+embedding insert
   5) 결과 반환              : chunkCount = chunkDocuments.size()
```

### 청킹 전략 (검증 완료)
- **Splitter:** `org.springframework.ai.transformer.splitter.TokenTextSplitter`
  (jar: `spring-ai-commons-2.0.0-M4.jar` 에서 소스 확인)
- **생성 방법 (확정):** 무인자 생성자는 `@Deprecated(since=2.0.0-M3, forRemoval=true)`. **빌더 사용 필수.**
  ```java
  TokenTextSplitter splitter = TokenTextSplitter.builder()
          .withChunkSize(800)   // 요구사항 4: 800 토큰
          .build();
  ```
  - 빌더 메서드명은 `with...` 접두사(`withChunkSize`, `withMinChunkSizeChars`, `withKeepSeparator` 등). **`chunkSize(...)` 아님 — `withChunkSize(...)` 임.** (소스 검증)
  - 참고: `DEFAULT_CHUNK_SIZE` 자체가 800이지만, 요구사항 명시값이므로 **명시적으로 `.withChunkSize(800)` 호출**한다 (의도 가시화).
  - 기본 인코딩 `CL100K_BASE` (jtokkit). 토큰 단위 분할. 별도 인코딩 지정 불필요.
- **분할 실행 (확정):** `TextSplitter`(부모)의 `apply(List<Document>)` 또는 `split(Document)` 사용.
  ```java
  List<Document> chunks = splitter.apply(List.of(parentDoc));
  // 또는 splitter.split(parentDoc)
  ```
  - 부모 Document의 metadata(`source`)는 자식 청크 Document로 **자동 복사**됨 (TextSplitter.doSplitDocuments가 metadataList를 청크에 전파).
- **오버랩:** TokenTextSplitter는 명시적 오버랩 파라미터가 없음(문장 경계 기반 분할). 요구사항에 오버랩 명세 없으므로 기본 동작 사용.
- **최소 청크:** `minChunkLengthToEmbed`(기본 5자) 미만 청크는 폐기됨 → 빈/공백 파일은 청크 0건 가능 (응답 message로 안내).

### 임베딩 모델
- `text-embedding-3-small` (application.yaml 확정), 1536차원. `vectorStore.add()` 내부에서 자동 호출.
- **배치/스트리밍:** 동기 단일 호출. 업로드 1건당 1트랜잭션. 대용량 파일 가드로 **업로드 크기 제한** 권장 (8절 백엔드 작업).

---

## 4. 검색 파이프라인 (기존 — 변경 없음)

이번 작업은 **인덱싱(쓰기)** 만 추가한다. 검색은 기존 `AIService.getDocs`가 담당:
- top-k = 4, cosine 유사도(HNSW 인덱스와 일치), 메타데이터 필터 미사용, rerank 미사용.
- 새 청크는 같은 `vector_store`에 적재되므로 별도 작업 없이 검색 대상에 포함된다.

---

## 5. 프롬프트 / Advisor 구성 (기존 — 변경 없음)

- 인덱싱 경로는 ChatClient/Advisor를 사용하지 않음 (LLM 호출 없이 EmbeddingModel만 사용).
- 챗봇 시스템 프롬프트는 기존 `AIService.SYSTEM_PROMPT` 유지.

---

## 6. API 스펙

| Method | Path | Content-Type | 요청 | 응답 | 설명 |
|--------|------|--------------|------|------|------|
| POST | `/user/rag/embedding` | `multipart/form-data` | `file`(파일), `source`(텍스트) | `application/json` `EmbeddingResponse` | 파일 청킹·임베딩·적재 |

> 기존 `AIRestController`(`@RequestMapping("/user/rag")`)에 `@PostMapping("/embedding")` 핸들러를 추가한다 → 전체 경로 `/user/rag/embedding` (요구사항 2 충족).

### 요청 (multipart/form-data)
- `file`: `MultipartFile` (필수)
- `source`: `String` (필수) — metadata에 저장될 출처 식별자

컨트롤러 시그니처(권장):
```java
@PostMapping(value = "/embedding",
             consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
             produces = MediaType.APPLICATION_JSON_VALUE)
public EmbeddingResponse embedding(@RequestParam("file") MultipartFile file,
                                   @RequestParam("source") String source) { ... }
```

### 응답 DTO — `EmbeddingResponse` (프론트가 파싱할 확정 JSON 키)
```json
{
  "success": true,
  "source": "사내규정-v1",
  "chunkCount": 7,
  "message": "7개 청크를 저장했습니다."
}
```
| 키 | 타입 | 설명 |
|----|------|------|
| `success` | boolean | 처리 성공 여부 |
| `source` | string | 저장된 source 값(에코백) |
| `chunkCount` | number | `vector_store`에 적재된 청크 수 |
| `message` | string | 사용자 표시용 메시지(성공/실패 사유) |

- **실패 시(빈 파일/추출 실패/예외):** HTTP 200 + `{ "success": false, "source": "...", "chunkCount": 0, "message": "<사유>" }` 로 통일 (프론트 분기 단순화). 입력 누락 등 4xx는 표준 에러로 둔다.
- **DTO 위치:** `kr.co.jihun.guisample.dto.EmbeddingResponse` (record 권장).

---

## 7. UI 시나리오

### 라우트 (기존)
- `GET /user/embedding` → `WEB-INF/views/user/embedding.jsp` (MainController 기존 매핑, 변경 없음)

### 화면 구성 (기존 common.css 재사용)
- 사이드바/토픽바는 현 embedding.jsp 레이아웃 유지.
- `.main-content` 안 `.page-header` 아래에 **업로드 폼 카드** 추가:
  - source 입력: `.form-field` / `.form-field__label` / `.form-field__input` (텍스트 input)
  - 파일 선택: `<input type="file">`
  - 업로드 버튼: `.search-form__btn` (기존 버튼 스타일 재사용)
  - 결과 표시 영역: 결과 메시지/청크 수 표시용 `<div id="embedResult">`

### 인터랙션 흐름
1. source 입력 + 파일 선택 → "업로드" 클릭
2. JS가 `FormData`(file, source)를 만들어 `fetch('/user/rag/embedding', { method:'POST', body: formData })`
   - **주의:** FormData 사용 시 `Content-Type` 헤더를 직접 지정하지 말 것(브라우저가 boundary 자동 설정).
3. 응답 JSON 파싱 → `success`면 `"<source> · <chunkCount>개 청크 저장 완료"`, 실패면 `message`를 결과 영역에 표시.
4. 처리 중 버튼 비활성화 + "업로드 중..." 표시.

### 중요 — 기존 embedding.jsp 정리
현재 `embedding.jsp`에는 **본문에 존재하지 않는 요소(`chatMessages`, `chatForm`, `chatInput` 등)를 참조하는 RAG 챗봇용 JS가 잔존**한다(동작 불가, 렌더 시 JS 에러 가능). 프론트 작업 시 이 잔존 스크립트를 **업로드용 스크립트로 교체/제거**한다.

---

## 8. 분배 지시

### spring-backend-engineer (선행 — 다른 작업의 blocker)
1. `dto/EmbeddingResponse` record 생성: `{ boolean success, String source, int chunkCount, String message }`
2. `service/EmbeddingService` 신규:
   - `ingest(MultipartFile file, String source)` 구현
   - 텍스트 추출: `new String(file.getBytes(), StandardCharsets.UTF_8)`
   - `new Document(text, Map.of("source", source))`
   - `TokenTextSplitter.builder().withChunkSize(800).build().apply(List.of(doc))`
   - `vectorStore.add(chunks)` 호출, `chunks.size()` 반환
   - 빈 파일/빈 청크/예외 처리 → `success=false` 응답 매핑
   - `VectorStore`는 starter 자동 구성 빈 주입(기존 AIService와 동일 방식)
3. `AIRestController`에 `@PostMapping("/embedding")` 핸들러 추가(6절 시그니처). `EmbeddingService` 주입.
4. 업로드 크기 제한 설정 검토: `spring.servlet.multipart.max-file-size` / `max-request-size` (application.yaml). 미설정 시 기본값 사용 가능하나 명시 권장.

### jsp-frontend-engineer (backend DTO/엔드포인트 확정 후)
1. `WEB-INF/views/user/embedding.jsp` 수정:
   - 잔존 RAG 챗봇 스크립트 제거/교체
   - 업로드 폼(source 텍스트 + file input + 버튼 + 결과 영역) 추가, common.css 클래스 재사용
   - `FormData` + `fetch('${pageContext.request.contextPath}/user/rag/embedding')` 로 업로드
   - 응답 `success/source/chunkCount/message` 파싱하여 결과 표시, XSS 방지 위해 `textContent` 사용
   - 버튼 비활성화/로딩 표시

### mybatis-data-engineer
- **작업 없음.** `vector_store` 테이블은 Spring AI pgvector starter가 `initialize-schema: true`로 자동 생성·관리하며, 적재는 `VectorStore.add()`가 수행한다. 별도 DDL/Mapper/ResultMap **불필요**.

### integration-qa (구현 완료 후)
1. `bootRun` 기동 후 `/user/embedding` 진입 → 텍스트 파일 + source 입력 업로드 → `success:true`, `chunkCount>0` 확인
2. DB `vector_store`에서 해당 청크의 `metadata->>'source'` 값과 `id`(uuid), `embedding` non-null 확인
3. 업로드한 문서 내용으로 `/user/rag` 챗봇 질의 → 컨텍스트 반영 여부 확인
4. 빈 파일 업로드 → `success:false` + 안내 메시지 확인
5. (선택) 800토큰 초과 긴 문서가 복수 청크로 분할되는지 확인

---

## 9. 의존성 / 확인 필요 사항

- **추가 의존성 없음** (텍스트 파일 한정). `TokenTextSplitter`는 `spring-ai-commons`(이미 전이 포함)에 존재 — 검증 완료.
- **(향후) 바이너리/PDF 지원 시:** `spring-ai-tika-document-reader` 등 DocumentReader 의존성 추가 필요. 이 경우 오케스트레이터에 의존성 추가 승인 요청.
- **검증 완료 API 시그니처:**
  - `TokenTextSplitter.builder().withChunkSize(int).build()` — sources jar 확인
  - `TextSplitter.apply(List<Document>)` / `split(Document)` — sources jar 확인
  - `new Document(String text, Map<String,Object> metadata)` — sources jar 확인 (id 미지정 → build 시 UUID 자동 생성)

---

## 변경 이력 추가 — 지식파일 관리 확장 (2026-06-11)

기존 "출처(source) 자유입력 단일 업로드 폼"을 **지식파일 목록 그리드 + 파일선택 팝업 + knowledge_files 메타 관리**로 확장.

### 핵심 설계 결정 (jar 검증 완료)
- **vector_store 스키마에 물리 `file_id` 컬럼 없음.** 기본 스키마(PgVectorStore.class strings 확인):
  `CREATE TABLE vector_store (id uuid DEFAULT uuid_generate_v4(), content text, metadata json, embedding vector(1536))`.
  → 요구사항 7.12/7.13의 "vector_store의 file_id"는 **metadata 안에서** 충족.
- **TokenTextSplitter 자동 메타데이터 키 = `parent_document_id`** (TextSplitter.class 바이트코드 확인: `createDocuments`가 각 청크 metadata에 `parent_document_id` = 부모 Document.getId() 주입). 사용자가 말한 "parents_file_id"의 실제 키명.
- 따라서: 부모 `new Document(fileId, text, Map.of("source", categoryId))` 로 생성 →
  각 청크 metadata = `{"source": categoryId, "parent_document_id": fileId}`, 청크 자신의 id = 새 UUID(7.5).
  → 7.5/7.6/7.7/7.8/7.12/7.13 동시 충족.
- **카테고리 결정**: 왼쪽 카테고리 그리드 선택 행의 categoryId 사용(사용자 확정). 미선택 시 등록 차단.
- **테이블 생성 안 함**(사용자 지시). knowledge_files 기존 존재 가정. DDL 실행/파일 생성 모두 미수행.

### API
| Method | Path | 요청 | 응답 |
|--------|------|------|------|
| GET | `/user/knowledge/knowledges` | page,rows | `{page,total,records,rows:[KnowledgeFileVO]}` (jqGrid) |
| POST | `/user/rag/embedding` | multipart `file`,`categoryId` | `EmbeddingResponse{success,fileId,categoryId,fileName,chunkCount,message}` |

### 업로드 파이프라인 (KnowledgeFileService.upload)
fileId=UUID → 텍스트추출(UTF-8) → EmbeddingService.embed(text,categoryId,fileId) (청킹·임베딩) →
`/home/sunjihun/mydev/upload_files/knowledge_files/{fileId}_{원본명}` 저장 → knowledge_files insert.
