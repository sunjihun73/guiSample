---
name: spring-backend-build
description: Spring Boot 4.0.x + Spring AI 백엔드 Java 코드를 작성할 때 사용. Controller(REST/JSP 라우팅), Service(트랜잭션), Config(빈 설정), VO/DTO(Lombok)를 kr.co.jihun.guisample 패키지 컨벤션에 맞게 생성. ChatClient·VectorStore 빈 구성, QuestionAnswerAdvisor 와이어링, Mapper 주입 패턴, REST 응답 DTO shape 안정성 보장. spring-backend-engineer 에이전트 전용. "Spring Boot 컨트롤러 생성", "Spring AI 통합", "RAG API 구현" 요청 시 반드시 트리거.
---

## 사용 시점

spring-backend-engineer 에이전트가 설계 문서(`_workspace/00_architect_design.md`)에 따라 백엔드 Java 코드를 작성할 때.

## 패키지 구조 규약

```
kr.co.jihun.guisample/
├── controller/user/
│   ├── MainController.java          (JSP 리턴)
│   └── *RestController.java         (REST API, @RestController + @RequestMapping)
├── service/
│   └── *Service.java                (@Service, @Transactional)
├── config/
│   ├── DataSourceConfig.java        (기존)
│   └── *Config.java                 (Spring AI 빈 등)
└── vo/
    └── *DTO.java                    (Lombok @Data @Builder)
```

## 코드 패턴

### REST Controller

```java
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@Slf4j
public class RagRestController {
    private final RagService ragService;

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest req) {
        return ragService.query(req);
    }
}
```

원칙:
- 응답을 `Map<String,Object>`로 반환하지 않는다. 항상 DTO
- `@RequestBody` 입력은 DTO로 수신. validation이 필요하면 `@Valid` + Bean Validation
- 예외는 `@ExceptionHandler`로 표준화

### Service

```java
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RagService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagChunkMapper chunkMapper;

    public QueryResponse query(QueryRequest req) {
        String answer = chatClient.prompt()
            .user(req.getQuestion())
            .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(5).build())
                .build())
            .call()
            .content();
        return QueryResponse.builder().answer(answer).build();
    }
}
```

원칙:
- 비즈니스 로직은 Service에. Controller는 얇게
- Mapper는 주입받아 사용. SQL을 Service에 직접 쓰지 않는다
- `@Transactional`은 쓰기 메서드에 필수

### Spring AI Config

```java
@Configuration
public class SpringAiConfig {
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("""
                당신은 친절한 한국어 RAG 비서입니다.
                제공된 컨텍스트만 사용하여 답변하세요.
                모르면 모른다고 답하세요.
                """)
            .build();
    }
}
```

원칙:
- `ChatModel`, `EmbeddingModel`, `VectorStore`는 spring-ai-starter가 자동 구성하므로 주입만 받는다
- 추가 빈만 직접 등록

### DTO

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResponse {
    private String answer;
    private List<SourceDTO> sources;
}
```

원칙:
- 필드명은 JSP/JS와 일치하게. 이름을 바꾸면 모든 소비자를 함께 수정
- 응답에 null 가능 필드가 있으면 명시적으로 문서화

## 작업 순서

1. `_workspace/00_architect_design.md`의 API 스펙·분배 지시 섹션을 정독
2. 패키지별로 생성 순서: DTO → Mapper(요청만, mybatis-data-engineer에 위임) → Service → Controller → Config
3. Mapper 시그니처가 필요하면 `SendMessage`로 mybatis-data-engineer에 요청하고, 응답 받기 전까지 다른 작업 진행
4. 작성 후 `./gradlew compileJava`로 컴파일 확인
5. `_workspace/01_backend_summary.md`에 파일 목록·엔드포인트·REST 응답 shape 표 작성
6. jsp-frontend-engineer에 `SendMessage`로 응답 shape 공유
7. integration-qa에 `SendMessage`로 검증 요청

## 흔한 함정

| 함정 | 해결 |
|------|-----|
| Spring Boot 4.x 에서 일부 import 경로가 바뀜 (jakarta.*) | jakarta.persistence/jakarta.servlet 사용 |
| `@Autowired` 필드 주입 | 생성자 주입 + `@RequiredArgsConstructor` |
| `Map<String,Object>` 응답 | DTO 클래스 정의 후 반환 |
| `@RestController` 인데 JSP 이름 리턴 | `@Controller` 사용 |

## 후속 작업

`_workspace/01_backend_summary.md`가 있으면 먼저 읽는다. 부분 수정 요청이면 해당 파일만 Edit. 새 엔드포인트 추가는 표에 행을 추가.
