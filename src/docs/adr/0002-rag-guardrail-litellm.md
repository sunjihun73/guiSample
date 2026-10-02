# 0002. RAG 채팅 파이프라인과 가드레일 위치(LiteLLM 위임)

- 상태(Status): 승인됨(Accepted)
- 날짜(Date): 2026-08-01

## 배경(Context)

RAG 채팅 요청 하나가 브라우저에서 출발해 벡터 검색과 LLM 호출을 거쳐 다시 브라우저로 돌아오기까지, 어떤 클래스의 어떤 메서드가 어떤 순서로 연결되는지가 코드만 봐서는 파악하기 어렵다. 특히 Spring AI의 어드바이저(Advisor) 체인은 실행 순서가 `order` 값으로만 암묵적으로 결정되고, 실제 실행 시점이 컨트롤러 리턴 시점이 아니라 Flux 구독 시점이라 흐름을 오해하기 쉽다.

또한 가드레일 구현 위치가 애플리케이션에서 LiteLLM으로 이동하면서, 초기 설계(커밋 `55fa822` "가드레일 위치를 벡터검색 앞으로 이동")가 보장하던 실행 순서가 달라졌다. 이 변화가 어떤 결과를 낳는지 기록이 필요하다.

## 결정(Decision)

입력 가드레일을 애플리케이션 어드바이저가 아닌 **LiteLLM 프록시에 위임**한다. 애플리케이션에는 가드레일 판정 코드를 두지 않으며, LiteLLM이 차단 시 반환하는 HTTP 400을 사용자 노출용 거부 메시지로 변환하는 책임만 진다.

기존 `KananaSafeGuardAdvisor`(어드바이저 단락 방식)는 제거한다.

## RAG 채팅 파이프라인 전체 흐름

```
[브라우저] rag.jsp  streamAnswer()
     │ fetch POST /user/rag/docs   (Accept: text/event-stream)
     ▼
[컨트롤러] AIRestController.getDocs()                    :174
     │ Flux 조립만 하고 즉시 리턴 (아직 아무것도 실행 안 됨)
     ▼
[서비스]   AIService.streamChat()                        :73
     │ chatClient.prompt().advisors(ragContextAdvisor).user(q).stream().chatResponse()
     ▼
[어드바이저] RagContextAdvisor.adviseStream()            :111
     │ ① pgvector 유사도 검색  ② 시스템 프롬프트에 <context> 주입
     ▼
[Spring AI 내부] ChatModelStreamAdvisor (체인 마지막, 자동 등록)
     │ OpenAiChatModel → WebClient
     ▼
[LiteLLM] POST http://litellm.jihun.com/v1/chat/completions
     │ ★ 입력 가드레일이 여기서 실행됨 (앱 밖)
     │   차단 시 → 400 Bad Request
     ▼
[실제 LLM] gpt-4o-mini
     │ SSE 토큰 스트림 역방향으로 되돌아옴
     ▼
브라우저 말풍선에 토큰 누적
```

가드레일은 이 저장소 안에 코드가 존재하지 않는다. 전부 LiteLLM 쪽에 있다. 따라서 실제 실행 순서는 "가드레일 → 벡터스토어 → LLM"이 아니라 **벡터스토어 → (LLM 호출 안에서) 가드레일 → LLM** 이다.

### 1. 프론트 → 컨트롤러

`src/main/webapp/WEB-INF/views/user/rag.jsp:480` `streamAnswer(query, onToken)`

`EventSource`는 GET만 지원하므로 `fetch` + `ReadableStream`을 직접 읽는다.

```javascript
var res = await fetch(DOCS_URL, { method:'POST', headers:{Accept:'text/event-stream'},
                                  body: JSON.stringify({query, categoryId, sessionId}) });
if (!res.ok) throw new Error('HTTP ' + res.status);
var reader = res.body.getReader();
```

이후 `\n\n`로 이벤트 경계를 자르고, `data:` 접두사를 `slice(5)`로 떼어 `onToken(text)`을 호출한다. Spring SSE는 `data:` 뒤에 공백을 붙이지 않으므로 `slice(5)`가 맞다.

### 2. 컨트롤러 — 조립만 하고 리턴

`AIRestController.getDocs()` `:174`

이 메서드는 Flux를 **조립만 하고 즉시 리턴**한다. 벡터 검색도 LLM 호출도 이 시점엔 일어나지 않는다. 실제 실행은 Spring MVC가 `SseEmitter`를 만들어 이 Flux를 구독(subscribe)하는 순간 시작된다.

```java
Flux<String> textStream = aiService.streamChat(query, categoryId)  // :193
    .doOnNext(resp -> captureMetadata(resp.getMetadata(), ...))    // 마지막 청크에서 model/토큰 확보
    .map(AIService::extractText)                                   // ChatResponse → 델타 텍스트
    .filter(text -> !text.isEmpty())                               // usage-only 빈 청크 제거
    .onErrorResume(AIRestController::toUserMessage)                // 400 → 거부 메시지
    .doOnNext(answer::append);                                     // 저장용 누적

return saveUser.thenMany(textStream).concatWith(saveAssistantTail);  // :235
```

마지막 줄이 실행 순서 계약이다.

- `saveUser` — user 메시지 INSERT (블로킹 JDBC → `boundedElastic`)
- `.thenMany(textStream)` — user 저장이 **끝난 뒤** 답변 스트림 시작
- `.concatWith(saveAssistantTail)` — 스트림이 **완료된 뒤** assistant 메시지 저장 + 세션 제목 자동 요약. `thenMany(Flux.empty())`이므로 클라이언트에는 아무것도 나가지 않는다.

### 3. 서비스 — 어드바이저 등록

`AIService.streamChat()` `:73`

```java
return chatClient.prompt()
        .advisors(ragContextAdvisor)                       // :82  체인에 어드바이저 등록
        .advisors(spec -> {                                // :84
            if (categoryId != null && !categoryId.isBlank())
                spec.param("category_id", categoryId);     // 어드바이저가 읽을 파라미터
        })
        .user(query)                                       // user 메시지 = 질문 원문
        .stream()
        .chatResponse();                                   // Flux<ChatResponse>
```

`spec.param("category_id", ...)`이 어드바이저와 통신하는 유일한 통로다. `AIService`는 벡터 검색을 직접 수행하지 않고 카테고리 값만 넘기며, 검색은 전적으로 어드바이저가 담당한다. 값이 있을 때만 넣는 이유는 `param`이 null을 받지 못하기 때문이다.

### 4. 어드바이저 체인

Spring AI의 `ChatClient`는 요청을 어드바이저 **체인**으로 통과시킨다. `order`가 작을수록 먼저 실행되고, 체인 맨 끝에는 Spring AI가 자동으로 넣는 `ChatModelStreamAdvisor`(order = `LOWEST_PRECEDENCE`)가 있어 **실제 모델 호출을 담당**한다.

현재 체인 구성 (`SpringAiConfig.java:30-33`):

| order | 어드바이저 | 출처 |
|---|---|---|
| `HIGHEST_PRECEDENCE + 100` | `RagContextAdvisor` | 프로젝트 코드 |
| `LOWEST_PRECEDENCE` | `ChatModelStreamAdvisor` | Spring AI 내장 (모델 호출) |

`RagContextAdvisor.adviseStream()` `:111`

```java
return Mono.fromCallable(() -> augmentWithContext(request))   // 블로킹 벡터 검색
        .subscribeOn(Schedulers.boundedElastic())             // 이벤트루프 막지 않게 오프로딩
        .flatMapMany(chain::nextStream);                      // ★ 다음 어드바이저로 넘김
```

`chain.nextStream(...)`이 "다음 단계로 진행" 스위치다. 이를 호출하지 않고 직접 응답을 만들면 그것이 **단락(short-circuit)** 이며, 제거된 `KananaSafeGuardAdvisor`가 unsafe 판정 시 사용하던 기법이 정확히 이것이다.

#### 4-1. 컨텍스트 주입

`RagContextAdvisor.augmentWithContext()` `:122`

```java
String context   = retrieveContext(userText, request.context().get("category_id"));  // :131
String systemText = SYSTEM_PROMPT.replace("{context}", context);                      // :132
Prompt augmented = request.prompt().augmentSystemMessage(systemText);                 // :135
return request.mutate().prompt(augmented).build();                                    // :136
```

두 가지 설계 포인트가 있다.

- **`replace()` 리터럴 치환** (`:132`) — Spring AI 템플릿 렌더링이 아니다. 문서 본문에 `{` `}`가 있어도 템플릿 변수로 재해석되지 않아 안전하다. 코드 조각이 포함된 사내 문서를 인덱싱해도 깨지지 않는다.
- **user 메시지 불변** (`:134` 주석) — 시스템 메시지만 증강한다. LLM에 전달되는 프롬프트는 `system(지침 + <context>문서</context>)` + `user(질문 원문)` 두 개다.

#### 4-2. 벡터 검색

`RagContextAdvisor.retrieveContext()` `:146`

```java
SearchRequest.builder().query(query).topK(4)          // topK는 SpringAiConfig:32에서 주입
    .filterExpression(new FilterExpressionBuilder()
        .eq("category_id", categoryId).build())       // 카테고리 있을 때만
vectorStore.similaritySearch(...)                     // :161
```

`similaritySearch()` 내부에서 두 가지가 일어난다.

1. **질문 텍스트 임베딩** — `EmbeddingModel`(`text-embedding-3-small`)이 LiteLLM `/v1/embeddings`를 호출한다. 즉 여기서도 네트워크 호출이 한 번 발생한다.
2. **pgvector 유사도 검색** — `application.yaml:41-46` 설정대로 HNSW 인덱스 + 코사인 거리로 `vector_store` 테이블을 조회한다. `filterExpression`은 `metadata->>'category_id' = ?` SQL로 번역된다.

결과 문서 본문을 `"\n\n---\n\n"`로 이어붙이고(`:169`), 결과가 없으면 `"(관련 문서가 없습니다.)"`(`:49`)를 넣는다. 시스템 프롬프트가 "컨텍스트에 근거가 없으면 찾지 못했다고만 답하라"(`:64`)고 지시하므로 이 경우 LLM은 환각 대신 모른다고 답한다.

`vector_store` 테이블 적재는 별개 경로다 — `POST /user/rag/embedding` → `EmbeddingService`가 800토큰 청킹 후 `vectorStore.add()`로 적재한다.

### 5. LLM 호출과 가드레일

체인 끝의 `ChatModelStreamAdvisor` → `OpenAiChatModel` → `WebClient` → `POST http://litellm.jihun.com/v1/chat/completions` (`stream: true`).

**입력 가드레일은 이 HTTP 요청을 받은 LiteLLM 내부에서 실행된다.** 애플리케이션 코드에는 없다. 판정 결과에 따라:

- **통과** → LiteLLM이 `gpt-4o-mini`로 프록시 → SSE 토큰이 되돌아옴
- **차단** → LiteLLM이 **HTTP 400** 응답 → `WebClientResponseException$BadRequest` → `AIRestController.toUserMessage()` `:287`가 거부 메시지 1건으로 변환해 스트림을 정상 종료시킴

### 6. 응답 경로

```
LiteLLM SSE 청크
  → OpenAiChatModel  : ChatResponse 로 파싱
  → ChatModelStreamAdvisor
  → RagContextAdvisor : flatMapMany 통과 (역방향엔 로직 없음 — 그대로 흘려보냄)
  → AIService         : Flux<ChatResponse>
  → AIRestController  : extractText 로 델타 텍스트 추출, answer 에 누적
  → SseEmitter        : "data:토큰\n\n"
  → rag.jsp onToken   : 말풍선에 append
```

`captureMetadata()`가 매 청크마다 `model`/`usage`를 덮어쓰지만, OpenAI 스트리밍은 **마지막 청크에만** usage를 실어 보내므로 결국 마지막 값이 남는다. 따라서 `application.yaml:37`의 `stream-usage: true`가 필수이며, 이 값이 `chat_detail`의 `prompt_tokens`/`completion_tokens`로 저장된다.

### 7. 스레드 분리

| 스레드 | 담당 |
|---|---|
| `http-nio-8080-exec-N` | 컨트롤러 메서드 실행(Flux 조립), SSE 쓰기 |
| `boundedElastic-N` | 벡터 검색, DB INSERT — 블로킹 작업 전용 |
| `reactor-http-nio-N` | LiteLLM 응답 수신 (WebClient 이벤트 루프) |

## 출력 가드레일 현황

**애플리케이션에는 출력 가드레일이 존재하지 않는다.** `RagContextAdvisor`는 응답 경로(`flatMapMany` 이후)에서 어떠한 검사도 하지 않으며, 컨트롤러도 텍스트를 그대로 브라우저로 전달한다.

LiteLLM에 `post_call` 가드레일이 설정되어 있다면 LiteLLM 내부에서 동작하는 것이며, 이 저장소 코드로는 확인할 수 없다.

애플리케이션 레벨에 추가한다면 위치는 다음과 같다.

```java
.flatMapMany(chain::nextStream)
.map(this::maskSensitive)     // ← 출력 가드레일 자리
```

다만 스트리밍이라 토큰이 조각 단위로 도착하므로 단순 문자열 매칭 필터는 경계에서 놓친다. 제대로 구현하려면 (a) 버퍼링 후 문장 단위 검사 또는 (b) LiteLLM `post_call` 가드레일 위임 중 하나를 선택해야 한다.

## 결과(Consequences)

### 장점

- 애플리케이션에서 가드레일 코드·설정·전용 `ChatClient` 빈이 모두 사라져 구성이 단순해졌다.
- 가드레일 모델 교체·정책 변경이 애플리케이션 재배포 없이 LiteLLM 설정만으로 가능하다.

### 단점 — 실행 순서 역전

커밋 `55fa822`가 보장하던 "가드레일 → 벡터 검색" 순서가 사라졌다. 위반 질문이 들어와도 다음 순서로 진행된다.

```
1. 질문 임베딩       → LiteLLM /v1/embeddings 호출  (비용 발생)
2. pgvector 검색     → DB 조회                      (비용 발생)
3. 시스템 프롬프트 조립
4. LiteLLM /v1/chat/completions → 이 시점에야 400 차단
```

차단될 질문임에도 임베딩 호출과 벡터 검색이 먼저 수행된다. 제거된 `KananaSafeGuardAdvisor`가 `order = HIGHEST_PRECEDENCE`로 `RagContextAdvisor`보다 앞서 단락시켰던 것이 정확히 이 낭비를 막기 위한 설계였다.

임베딩 1회 + 인덱스 조회 1회 수준이므로 토이 프로젝트 규모에서는 실비가 미미하다고 판단해 현 구조를 유지한다. 비용·지연이 문제가 되면 LiteLLM 가드레일 전용 엔드포인트를 호출하는 어드바이저를 `RagContextAdvisor`보다 앞선 `order`로 추가해 단락시키는 방식을 재검토한다.

### 단점 — 차단이 예외로 전달됨

LiteLLM은 차단을 정상 응답이 아닌 HTTP 400으로 알린다. SSE 엔드포인트에서 이 예외를 그대로 흘리면 응답 커밋 전에 DispatcherServlet 예외 경로로 빠져 **HTTP 500**이 되고, `Accept: text/event-stream` 이라 에러 표현조차 만들 수 없어 `HttpMediaTypeNotAcceptableException: No acceptable representation`이 발생한다.

따라서 이 경로의 모든 실패는 예외로 전파하지 않고 "텍스트 1건 방출 후 정상 종료(200)"로 처리해야 한다. `AIRestController.toUserMessage()` `:287`가 이 책임을 진다. 400 응답 본문의 표식(`guardrail`, `violated`, `unsafe`, `content safety`, `blocked`, `moderation`)으로 가드레일 차단과 설정 오류(모델명 오타 등)를 구분하며, 두 경우 모두 응답 본문 전문을 로그로 남긴다.

## 관련 문서

- [0001. pgvector를 벡터 저장소로 사용](0001-use-pgvector.md)
