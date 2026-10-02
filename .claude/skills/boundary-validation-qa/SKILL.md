---
name: boundary-validation-qa
description: 5대 경계면(REST DTO↔JSP/JS, MyBatis ResultMap↔DTO, Mapper 파라미터↔XML #{}, 라우팅 URL↔호출 URL, Spring AI 설정↔코드 모델명)의 정합성을 양쪽 파일을 동시에 읽어 shape 단위로 교차 비교. 단순 존재 확인이 아니라 필드명·중첩 구조·타입까지 비교. 모듈 단위로 점진적(incremental)으로 실행하여 빌드/런타임에서 깨질 부분을 사전 발견. integration-qa 에이전트 전용. "정합성 검증", "경계면 QA", "shape 비교", "통합 검증" 요청 시 반드시 트리거.
---

## 사용 시점

integration-qa 에이전트가 다른 팀원의 산출물을 검증할 때.
**전체 완성 후 1회가 아니라 모듈 단위로 점진적으로** 실행.

## 핵심 원칙 — 존재 확인이 QA가 아니다

"파일이 있다", "메서드가 있다"는 검증이 아니다. **양쪽 파일을 동시에 Read하여 필드 shape을 비교**해야 한다.

## 5대 경계면

### 경계면 1: REST API ↔ JSP/JS

**A쪽**: `controller/user/*RestController` 메서드 반환 DTO
**B쪽**: `WEB-INF/views/user/*.jsp` 또는 `static/js/*.js`의 응답 파싱 코드

**검증 절차:**
1. RestController의 `@PostMapping`/`@GetMapping` 메서드를 Read
2. 반환 타입의 DTO 클래스 Read → 필드 목록 확보
3. JSP/JS에서 같은 URL을 호출하는 부분을 grep → fetch 응답을 어떻게 파싱하는지 Read
4. JS가 참조하는 모든 필드(`data.X`, `data.y.z`)가 DTO에 실제 존재하는지 확인

**버그 패턴:**
- DTO 필드 카멜케이스 vs JS의 스네이크케이스 (Jackson 기본 카멜)
- DTO 필드명 변경 후 JS 미수정
- DTO에 nested 객체인데 JS가 평면으로 가정
- DTO가 `List<X>`인데 JS가 단일 객체로 처리

### 경계면 2: MyBatis ResultMap ↔ DTO

**A쪽**: `resources/mapper/*Mapper.xml`의 `<resultMap>` `<result column property>`
**B쪽**: `vo/*DTO.java` 필드

**검증 절차:**
1. ResultMap의 모든 `<result>` 요소 읽기
2. 각 `property="x"`에 대해 DTO의 `private ... x` 필드 존재 확인
3. `<id>` 컬럼도 동일하게 검증
4. typeHandler가 있으면 핸들러 클래스 존재 확인

**버그 패턴:**
- property 오타 → 런타임에 setter 못 찾아 NPE
- 컬럼 타입(jsonb, vector)과 DTO 타입 불일치
- DTO 필드 이름 변경 후 ResultMap 미수정

### 경계면 3: Mapper 파라미터 ↔ XML #{}

**A쪽**: Mapper interface 메서드 시그니처 (`@Param` 포함)
**B쪽**: XML의 `#{...}` placeholder

**검증 절차:**
1. interface의 각 메서드 파라미터 이름과 `@Param` 값을 확보
2. 같은 ID의 XML statement에서 사용된 모든 `#{}` 확인
3. `@Param("x")`가 있으면 XML에서 `#{x.field}` 형태. 단일 비-Param 객체면 `#{field}` 직접

**버그 패턴:**
- `@Param` 없이 객체 전달 + XML에서 `#{0.field}` 같은 모호한 참조
- `@Param("chunk")`인데 XML에 `#{chunkInfo.field}` 같은 오타

### 경계면 4: 라우팅 URL ↔ 호출 URL

**A쪽**: Controller의 `@RequestMapping`/`@GetMapping`/`@PostMapping` 경로
**B쪽**: JSP/JS의 fetch URL, 폼 action

**검증 절차:**
1. 모든 컨트롤러의 URL 패턴 수집 (`@RequestMapping` 클래스 + 메서드)
2. JSP/JS에서 fetch/form action URL을 grep
3. 1:1 매칭. 누락된 라우트 또는 미사용 라우트 보고

**버그 패턴:**
- 컨트롤러 `@RequestMapping("/api/rag")` + 메서드 `@PostMapping("/query")` → 실제 URL은 `/api/rag/query`. JS가 `/rag/query`만 호출
- HTTP 메서드 불일치 (Controller GET vs JS POST)
- 경로 변수 vs 쿼리 파라미터 혼동

### 경계면 5: Spring AI 설정 ↔ 코드

**A쪽**: `application.yaml`의 `spring.ai.*` 설정
**B쪽**: Service/Config에서 사용하는 모델명, 차원, 인덱스 init

**검증 절차:**
1. yaml의 `spring.ai.openai.chat.options.model`, `embeddings.options.model` 확인
2. yaml의 `spring.ai.vectorstore.pgvector.initialize-schema` 확인
3. Service 코드에서 사용된 vector 차원이 임베딩 모델 차원과 일치하는지
4. 도메인 테이블 DDL의 `vector(n)` n이 임베딩 차원과 일치하는지

**버그 패턴:**
- yaml의 embedding 모델이 text-embedding-3-large(3072)인데 DDL은 vector(1536)
- `initialize-schema: true`인데 도메인 테이블과 컬럼명 충돌
- 코드에 모델명 하드코딩 (yaml의 값이 무시됨)

## 점진적 실행 — 모듈 단위

전체 빌드 완료를 기다리지 않는다. 각 팀원의 `SendMessage` 완료 통지가 오면 그 시점에 가능한 경계면부터 검증:

| 시점 | 검증 가능한 경계면 |
|------|----------------|
| mybatis-data-engineer 완료 | 경계면 3 (Mapper↔XML) |
| spring-backend-engineer 완료 | 경계면 2 (ResultMap↔DTO), 경계면 5 (AI 설정↔코드) |
| jsp-frontend-engineer 완료 | 경계면 1 (REST↔JS), 경계면 4 (라우팅↔호출) |

## 보고 형식

`_workspace/qa_findings.md`에 append:

```markdown
## {YYYY-MM-DD HH:MM} 라운드 N

### [BLOCKER] 경계면 1 — REST DTO ↔ JSP/JS
- 파일A: vo/QueryResponse.java:15 (`private String answer;`)
- 파일B: static/js/rag.js:24 (`data.result`)
- 기대: data.answer
- 실제: data.result
- 수정 제안: rag.js의 `data.result`를 `data.answer`로 변경

### [MAJOR] 경계면 2 — ResultMap ↔ DTO
- ...

### [MINOR] ...
```

치명도:
- **BLOCKER**: 빌드 실패 또는 런타임 NPE/예외 확실. 즉시 `SendMessage`로 해당 팀원 통지
- **MAJOR**: 정상 흐름은 동작하나 엣지 케이스에서 깨짐
- **MINOR**: 기능에 영향 없으나 일관성/가독성 개선 권장

## 작업 순서

1. 검증 대상 산출물 위치를 SendMessage로 수신
2. 해당 모듈에서 가능한 경계면 식별 (위 표)
3. 양쪽 파일을 동시에 Read
4. 발견 사항을 치명도 분류 후 `_workspace/qa_findings.md`에 append
5. BLOCKER만 즉시 해당 팀원에 SendMessage. MAJOR/MINOR는 라운드 종료 시 묶어서 보고
6. 모든 모듈 완료 후 최종 라운드 검증 — 신규 회귀 여부

## 흔한 함정

| 함정 | 해결 |
|------|-----|
| 한쪽만 보고 결론 | 절대 양쪽 Read 전에 보고하지 않는다 |
| 존재 확인으로 만족 | shape/필드명/타입까지 비교 |
| 너무 늦은 검증 (전체 완성 후) | 모듈 완성 즉시 점진적으로 실행 |
| BLOCKER 누적 후 일괄 통지 | BLOCKER는 즉시 통지하여 다음 작업 차단 방지 |

## 후속 작업

기존 `_workspace/qa_findings.md`의 미해결 finding(BLOCKER/MAJOR)을 먼저 확인. 회귀 발생 여부를 우선 검증한 뒤 새 검증으로 이동.
