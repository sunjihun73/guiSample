# guiSample

Spring Boot 4 + Spring AI(RAG) + MyBatis + PostgreSQL(pgvector) + JSP로 구성된 Sample Toy Project 입니다. 
프로젝트 마스터 CRUD와, 문서를 임베딩해 적재하고 그 문서를 근거로 답변하는 RAG 챗봇을 제공합니다.

## 주요 기능

- **프로젝트 관리** — 프로젝트 마스터 목록 조회(검색·페이징), 등록, 수정 (jqGrid 기반 화면)
- **문서 임베딩** — 텍스트 파일 업로드 → 800토큰 단위 청킹 → 임베딩 → pgvector(`vector_store`) 적재
- **RAG 챗봇** — 적재된 문서를 유사도 검색해 컨텍스트로 주입하고, 답변을 SSE로 토큰 스트리밍

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어/런타임 | Java 17 |
| 프레임워크 | Spring Boot 4.0.6, Spring MVC |
| AI | Spring AI 2.0.0-M4 (OpenAI chat `gpt-4o-mini`, embedding `text-embedding-3-small`) |
| 벡터 스토어 | PostgreSQL + pgvector (HNSW, cosine, 1536차원) |
| 데이터 접근 | MyBatis 4.0.1 (mybatis-spring-boot-starter) |
| 뷰 | JSP + JSTL, Vanilla JS, 단일 `common.css` |
| 빌드 | Gradle 9.4.1 (Wrapper), WAR 패키징 |
| 보조 | Lombok |

## 사전 요구사항

1. **JDK 17** (Gradle toolchain 으로 자동 해석)
2. **PostgreSQL** + **pgvector** 확장 설치
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
   - 기본 접속값: `jdbc:postgresql://localhost:5432/postgres`, user/password `postgres/postgres` (`application.yaml` 에서 변경 가능)
   - `vector_store` 테이블은 `spring.ai.vectorstore.pgvector.initialize-schema: true` 설정으로 **애플리케이션 기동 시 자동 생성**됩니다.
   - `project_master` 테이블은 별도로 준비해야 합니다 (MyBatis 매퍼 `ProjectMasterMapper.xml` 참고).
3. **OpenAI API Key** — 임베딩/챗봇 호출에 필요

## 환경 변수 설정

비밀값은 gitignore 처리된 `.env` 파일로 주입합니다 (`application.yaml` 의 `spring.config.import: optional:file:.env[.properties]`).

프로젝트 루트에 `.env` 파일을 만들고 키를 추가하세요:

```properties
OPENAI_API_KEY=sk-...
```

## 빌드 & 실행

```bash
./gradlew bootRun        # 임베디드 Tomcat 으로 실행 (DevTools 활성)
./gradlew build          # 컴파일 + 패키징
./gradlew test           # 전체 테스트
./gradlew clean build    # 클린 후 풀 빌드
```

- 실행 후 접속: <http://localhost:8080/user/dashboard>
- WAR 산출물: `build/libs/guiSample-0.0.1-SNAPSHOT.war`

### 배포 모드

- **임베디드 실행** (`bootRun`): `GuiSampleApplication` 이 임베디드 Tomcat 기동
- **외부 WAR 배포**: `ServletInitializer`(`SpringBootServletInitializer` 확장) 로 외부 컨테이너 배포 지원

## 화면 (GET)

`MainController` (`/user`) 가 JSP 뷰를 라우팅합니다.

| 경로 | 화면 | 설명 |
|------|------|------|
| `/user/` , `/user/dashboard` | dashboard.jsp | 대시보드 |
| `/user/projects` | projects.jsp | 프로젝트 목록/등록/수정 (jqGrid) |
| `/user/rag` | rag.jsp | RAG 챗봇 |
| `/user/embedding` | embedding.jsp | 파일 업로드 임베딩 |

## REST API

### 프로젝트 마스터 — `ProjectMasterRestController` (`/user/project`)

| Method | Path | 요청 | 응답 |
|--------|------|------|------|
| GET | `/user/project/projects` | `page`, `rows`, `projectName`(선택) | jqGrid 형식 `{ page, total, records, rows[] }` |
| POST | `/user/project/projects` | `ProjectMasterDTO` (JSON) | `{ success, projectId }` |
| PATCH | `/user/project/projects/{projectId}` | `ProjectMasterDTO` (JSON) | `{ success, projectId, message? }` |

### RAG — `AIRestController` (`/user/rag`)

| Method | Path | 요청 | 응답 |
|--------|------|------|------|
| POST | `/user/rag/docs` | `{ "query": "질문" }` | `text/event-stream` — 답변 토큰 SSE 스트림 |
| POST | `/user/rag/embedding` | `multipart/form-data` (`file`, `source`) | `{ success, source, chunkCount, message }` (성공/실패 모두 HTTP 200) |

#### 임베딩 파이프라인

```
파일 업로드(file + source)
  → UTF-8 텍스트 추출
  → Document(metadata={"source": <입력값>}) 생성
  → TokenTextSplitter(chunkSize=800) 청킹
  → VectorStore.add()  ──▶  vector_store 적재
        · id          : UUID 자동 생성
        · content     : 청크 텍스트
        · embedding   : text-embedding-3-small(1536차원)
        · metadata    : {"source", "parent_document_id", "chunk_index", "total_chunks"}
```

> `parent_document_id`/`chunk_index`/`total_chunks` 는 Spring AI `TextSplitter` 가 청킹 시 자동으로 부여하는 추적용 메타데이터입니다.

#### RAG 검색

`AIService.getDocs()` 가 질의를 임베딩해 `vector_store` 에서 top-k(4) 유사 문서를 검색하고, 그 컨텍스트를 시스템 프롬프트에 주입한 뒤 `gpt-4o-mini` 응답을 스트리밍합니다.

## 프로젝트 구조

```
src/main/
├── java/kr/co/jihun/guisample/
│   ├── GuiSampleApplication.java        # 임베디드 부트스트랩
│   ├── ServletInitializer.java          # 외부 WAR 배포용
│   ├── config/
│   │   ├── DataSourceConfig.java        # 단일 PostgreSQL DataSource + MyBatis 수동 구성
│   │   └── SpringAiConfig.java          # ChatClient 빈
│   ├── controller/user/
│   │   ├── MainController.java          # JSP 뷰 라우팅
│   │   ├── ProjectMasterRestController.java
│   │   └── AIRestController.java        # RAG 챗봇 + 임베딩 업로드
│   ├── service/
│   │   ├── ProjectMasterService.java
│   │   ├── AIService.java               # RAG 검색 + 답변 스트리밍
│   │   └── EmbeddingService.java        # 청킹 + 임베딩 적재
│   ├── mapper/ProjectMasterMapper.java
│   ├── vo/ProjectMasterDTO.java
│   └── dto/EmbeddingResponse.java
├── resources/
│   ├── application.yaml                 # 서버/DataSource/Spring AI 설정
│   └── mapper/ProjectMasterMapper.xml   # MyBatis SQL
└── webapp/WEB-INF/views/user/*.jsp      # JSP 뷰
src/main/resources/static/css/common.css # 공통 스타일(단일 파일)
src/docs/adr/                            # 아키텍처 결정 기록(ADR)
```

## 아키텍처 메모

- **단일 DataSource 공유** — `DataSourceConfig` 가 PostgreSQL DataSource 하나를 선언하여 Spring AI pgvector 와 MyBatis 매퍼가 함께 사용합니다. 직접 DataSource 빈을 선언하므로 Boot 의 DataSource/MyBatis 자동구성은 비활성화되고, `SqlSessionFactory`(언더스코어→카멜케이스 매핑, `vo` 타입 별칭, `classpath:mapper/**/*.xml`)를 명시적으로 구성합니다.
- **pgvector 채택 배경** — `src/docs/adr/0001-use-pgvector.md` 참고 (토이 프로젝트에서 RAG 구현을 위해 기존 PostgreSQL 에 pgvector 확장 사용).
- **CSS** — 화면별 인라인 스타일 대신 `common.css` 단일 파일로 공통화되어 있습니다.

## 라이선스

내부 샘플 프로젝트.
