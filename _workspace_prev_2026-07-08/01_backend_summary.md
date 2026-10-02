# 01. 백엔드 구현 요약 — 파일 업로드 → 청킹 → 임베딩 → pgvector 적재

> 작성: spring-backend-engineer | 날짜: 2026-06-05
> 기준 설계: `_workspace/00_architect_design.md` (6절 API 스펙, 8절 분배 지시 backend)
> 컴파일 검증: `./gradlew compileJava` → BUILD SUCCESSFUL

## 생성/수정 파일

| 구분 | 경로 |
|------|------|
| 신규 | `src/main/java/kr/co/jihun/guisample/dto/EmbeddingResponse.java` (record) |
| 신규 | `src/main/java/kr/co/jihun/guisample/service/EmbeddingService.java` |
| 수정 | `src/main/java/kr/co/jihun/guisample/controller/user/AIRestController.java` (핸들러 추가) |
| 수정 | `src/main/resources/application.yaml` (multipart 크기 제한) |

> 패키지 결정: 설계 문서 6·8절이 `kr.co.jihun.guisample.dto.EmbeddingResponse`(record)로 명시 지정 → 설계대로 `dto` 패키지 사용. (기존 `vo`는 Lombok 기반 가변 DTO 컨벤션이며, 응답 전용 불변 record는 설계 지정 위치를 따름.)

## 엔드포인트

- **POST `/user/rag/embedding`**
  - consumes: `multipart/form-data`
  - produces: `application/json`
  - 멀티파트 필드: `file` (MultipartFile, 필수), `source` (String, 필수)
  - 컨트롤러 시그니처:
    ```java
    public EmbeddingResponse embedding(@RequestParam("file") MultipartFile file,
                                       @RequestParam("source") String source)
    ```

## REST 응답 shape (프론트가 파싱할 확정 JSON 키 — jsp-frontend-engineer 안내용)

성공/실패 **모두 HTTP 200** + 아래 동일 shape (프론트 분기는 `success` 불리언만 보면 됨):

```json
{
  "success": true,
  "source": "사내규정-v1",
  "chunkCount": 7,
  "message": "7개 청크를 저장했습니다."
}
```

| 키 | 타입 | 비고 |
|----|------|------|
| `success` | boolean | 처리 성공 여부 |
| `source` | string | 입력 source 에코백 (실패 시에도 입력값 그대로, source 누락이면 null 가능) |
| `chunkCount` | number(int) | 적재된 청크 수. 실패 시 0 |
| `message` | string | 사용자 표시용 메시지 |

### 실패 메시지 예시 (success=false, chunkCount=0)
- 파일 없음/빈 파일: `"업로드된 파일이 없습니다."`
- source 누락: `"source(출처 식별자)를 입력해 주세요."`
- 본문 공백: `"빈 파일입니다. 내용이 있는 텍스트 파일을 업로드해 주세요."`
- 청크 0건: `"추출된 내용이 너무 짧아 저장할 청크가 없습니다."`
- 예외: `"파일 처리 중 오류가 발생했습니다: <원인>"`

> 프론트 주의(설계 7절 재확인): FormData 사용 시 `Content-Type` 헤더 직접 지정 금지(브라우저 boundary 자동). 결과 표시는 `textContent`로 XSS 방지.

## 주요 빈

- `EmbeddingService` (`@Service`) — `VectorStore` 생성자 주입(starter 자동구성, 기존 `AIService`와 동일 방식). `TokenTextSplitter.builder().withChunkSize(800).build().apply(List.of(parent))` 청킹 후 `vectorStore.add(chunks)`.
- 컨트롤러는 기존 `AIRestController`(`@RequestMapping("/user/rag")`, `@RequiredArgsConstructor`)에 `embeddingService` 필드 추가.

## 설정 변경 (application.yaml)

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

## mybatis-data-engineer 요구 Mapper 시그니처

- **없음.** `vector_store`는 pgvector starter가 `initialize-schema: true`로 자동 생성·관리하며 적재는 `VectorStore.add()`가 수행. 별도 DDL/Mapper/ResultMap 불필요(설계 8절과 동일).

## 검증된 Spring AI API (sources jar 2.0.0-M4 확인)

- `TokenTextSplitter.builder()` → `Builder.withChunkSize(int)` → `build()`
- `TextSplitter.apply(List<Document>)`
- `new Document(String text, Map<String,Object> metadata)` (id 미지정 → UUID 자동 부여)

## 2026-06-12 categoryId 필터 추가

- `AIRestController.getDocs`: body에서 `categoryId`(선택)를 추가 추출하여 서비스로 전달.
  - 요청 계약: `POST /user/rag/docs` body `{ "query": "...", "categoryId": "UUID(선택)" }`
  - `categoryId` null/누락/공백이면 필터 없이 전체 벡터 검색 (기존 동작 유지).
- `AIService.getDocs(String query, String categoryId)`로 시그니처 변경.
  - `FilterExpressionBuilder().eq("category_id", categoryId).build()` → `SearchRequest.Builder.filterExpression(Filter.Expression)`.
  - PgVectorStore가 `metadata->>'category_id' = ?` JSONB 조건으로 변환.
- 응답 shape(text/event-stream 토큰 스트림)은 변경 없음.
- `./gradlew compileJava` 통과 확인 (Spring AI 2.0.0-M4).

## 후속 추가 (2026-06-15) — 청크 목록 조회 API
- `KnowledgeRestController`: GET `/user/knowledge/knowledges/chunkings`, param `parent_document_id`, 반환 `List<KnowledgeChunkVO>`(JSON 배열, chunk_index ASC)
- `KnowledgeFileService.selectKnowledgeChunkList(String parentDocumentId)`
