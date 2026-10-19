---
name: spring-backend-build
description: guiSample 의 Spring Boot 4 + Spring AI(2.0.0-M4) + Flowable 백엔드 Java 코드를 작성하는 방법. controller/user 의 REST·JSP 라우팅, service, config, dto, advisor, scm 구현체, src/test 단위 테스트를 기존 컨벤션(dto 패키지, HashMap 매퍼 파라미터, jqGrid 응답, LoginUserSession 소유권, SSE onErrorResume, 두 프로파일 yaml)에 맞춘다. spring-backend-engineer 가 사용한다. "컨트롤러/서비스 추가", "REST API 구현", "Spring AI 어드바이저 수정", "ScmClient 구현체 추가", "워크플로우 서비스 수정" 요청 시 트리거.
---

## 사용 시점

spring-backend-engineer 가 설계 문서에 따라 백엔드 코드를 작성할 때.

## 패키지 구조 (실제)

```
kr.co.jihun.guisample/
├── controller/user/   MainController(JSP 라우팅 /user/**), *RestController
├── service/           *Service
├── config/            DataSourceConfig, FlowableConfig, SecurityConfig, SpringAiConfig
├── dto/               *DTO, *Response, *Result (typeAliasesPackage 대상)
├── mapper/            MyBatis interface (data 담당)
├── advisor/           RagContextAdvisor, RagRetrievalSettings
├── scm/               ScmClient, ScmClientResolver, github/GitHubScmClient
├── session/           LoginUserSession, SessionUser
└── filter/            KeycloakUserProvisioningFilter (빈 아님)
```

## 코드 패턴

### 목록 REST — jqGrid shape + 소유권 (기존 `KnowledgeRestController` 형태)

```java
@GetMapping("/items")
public Map<String, Object> itemList(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int rows)
{
    HashMap<String, Object> param = new HashMap<>();
    /* 소유자 조건 — 총건수와 목록이 같은 조건을 봐야 하므로 count 보다 먼저 넣는다. */
    param.put("createUserId", loginUserSession.requireUserName());

    int totalRecords = itemService.countItem(param);
    int totalPages   = totalRecords == 0 ? 0 : (int) Math.ceil((double) totalRecords / rows);

    param.put("startRow", (page - 1) * rows);   // mapper 는 pageSize != null 일 때만 LIMIT
    param.put("pageSize", rows);

    Map<String, Object> result = new HashMap<>();
    result.put("page", page);
    result.put("total", totalPages);
    result.put("records", totalRecords);
    result.put("rows", itemService.selectItemList(param));
    return result;
}
```

- 목록은 이 shape 를 유지한다(화면의 jqGrid 가 이 키를 읽는다). 단건·명령 응답은 DTO(`RepoPushResult` 등)
- 매퍼 파라미터는 `HashMap<String,Object>`(기존 매퍼와 동일). 키 이름은 data 와 합의한 것을 그대로

### 소유권

- 기준값은 **컨트롤러에서** `loginUserSession.requireUserName()`. 값이 없으면 예외 — 조건이 조용히 빠져 남의 데이터가 보이는 것을 막는다
- 소유자 컬럼이 없는 자식 데이터(`chat_detail`, `vector_store`)는 상위 엔터티 소유권을 먼저 확인하고 아니면 빈 목록 (`ChatService.selectMessages` 패턴)
- 소유권 예외는 설계 문서에 근거가 있을 때만

### 비동기·SSE

- `LoginUserSession` 은 요청 스레드 바인딩 프록시 → `Schedulers.boundedElastic()` 안에서 호출하면 실패. 컨트롤러 본문에서 꺼내 파라미터로 전달 (`AIRestController.getDocs` 참조)
- `text/event-stream` 엔드포인트는 예외를 밖으로 던지지 않는다(Accept 협상 실패 → 500 + 빈 본문). `onErrorResume` 으로 텍스트 1건 + 정상 종료
- 스트리밍 경로의 블로킹 호출은 `boundedElastic` 으로 오프로딩

### Spring AI

- `ChatModel`·`EmbeddingModel`·`VectorStore` 는 스타터 자동구성, `ChatClient` 는 `SpringAiConfig`
- RAG 검색은 `RagContextAdvisor` → `HybridRetrievalService`. 요청별 값은 `.advisors(spec -> spec.param("category_id", ...).param("user_name", ...))`
- `QuestionAnswerAdvisor` 를 새로 붙이지 않는다(하이브리드 검색·소유권 필터 우회)
- 프롬프트 컨텍스트 삽입은 `String.replace` 리터럴 치환

### 트랜잭션 · DataSource

- 앱 기본 트랜잭션 매니저는 `DataSourceConfig.transactionManager()`(`@Primary`). 지우거나 덮지 않는다 — 지우면 `@Transactional` 이 워크플로우 DB 를 잡는다
- 엔진(5433)과 앱(5432) 변경은 한 트랜잭션이 아니다. 설계 문서의 순서·보상을 따른다
- 외부 HTTP(SCM 등)를 기다리는 구간에는 트랜잭션을 걸지 않는다

### SCM

- 새 저장소 종류 = `ScmClient` 구현체 `@Component` + `supports(repoUrl)`. 쓰기는 `putFile` 오버라이드
- `RestClient` 사용. 경로는 `UriBuilder.pathSegment(...)` 로 쪼갠다(`GitHubScmClient.contentsUri` 와 회귀 테스트 참조)

### 설정

- `application-local.yaml`·`application-e2e.yaml` 을 **함께** 수정(현재 차이는 chat 모델명뿐). `application.yaml` 은 프로파일 선택만

### 단위 테스트

- `src/test` 는 스프링 컨텍스트·DB·네트워크 없는 순수 테스트만 둔다(매칭·파싱·URI 조립 같은 로직). 컨텍스트가 필요한 검증은 e2e 몫

## 작업 순서

1. 설계 문서 §3(소유권)·§5(API)·§8(분배) 정독, 비슷한 기존 컨트롤러·서비스를 하나 골라 읽는다
2. DTO → (매퍼 시그니처 요청) → Service → Controller → Config
3. `./gradlew compileJava`, 해당 시 `./gradlew test`
4. `_workspace/01_backend_summary.md` 작성
5. `frontend` 에 응답 shape, `qa` 에 검증 요청 `SendMessage`

## 흔한 함정

| 함정 | 해결 |
|------|------|
| DTO 를 `vo/` 에 생성 | `dto/` — typeAliasesPackage 와 ResultMap type 이 이 패키지를 본다 |
| count 뒤에 소유자 조건 추가 | 총건수와 목록 불일치. 조건을 count 앞에 |
| `boundedElastic` 안에서 `loginUserSession` 호출 | 컨트롤러에서 미리 꺼내 전달 |
| SSE 메서드에서 예외 throw | `onErrorResume` 으로 정상 종료 |
| `KeycloakUserProvisioningFilter` 를 `@Component` 로 | 이중 실행. `SecurityConfig` 에서 직접 생성 |
| 한쪽 프로파일 yaml 만 수정 | 두 파일 함께 |
| `@Autowired` 필드 주입 | 생성자 주입 + `@RequiredArgsConstructor` |

## 후속 작업

`_workspace/01_backend_summary.md` 를 먼저 읽는다. 부분 수정이면 해당 파일만 Edit, 새 엔드포인트는 표에 행 추가.
