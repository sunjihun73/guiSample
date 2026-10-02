package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.dto.ChatDetailDTO;
import kr.co.jihun.guisample.dto.ChatMasterDTO;
import kr.co.jihun.guisample.dto.EmbeddingResponse;
import kr.co.jihun.guisample.dto.MessageListResponse;
import kr.co.jihun.guisample.dto.MessageRow;
import kr.co.jihun.guisample.dto.SessionCreateResponse;
import kr.co.jihun.guisample.dto.SessionListResponse;
import kr.co.jihun.guisample.dto.SessionRow;
import kr.co.jihun.guisample.service.AIService;
import kr.co.jihun.guisample.service.ChatService;
import kr.co.jihun.guisample.service.KnowledgeFileService;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Spring AI 기반 RAG 기능 REST 엔드포인트.
 * <p>세션/대화 저장(chat_master/chat_detail) 엔드포인트를 함께 제공한다.
 *
 * <p><b>사용자 귀속</b> — chat_master 조회 조건에 실을 로그인 사용자명은 이 컨트롤러에서
 * {@link LoginUserSession} 으로 세션에서 꺼낸다.
 *
 * <p><b>주의</b> — {@code /docs} 의 저장 로직은 {@link Schedulers#boundedElastic()} 의
 * 별도 스레드에서 돈다. 그 스레드에는 요청 컨텍스트가 없어 세션 프록시를 읽을 수 없으므로,
 * 사용자명은 반드시 <b>컨트롤러 메서드 본문(요청 스레드)에서 미리 꺼내</b> 람다에 넘긴다.
 */
@RestController
@RequestMapping("/user/rag")
@RequiredArgsConstructor
@Slf4j
public class AIRestController
{
    private final AIService aiService;
    private final ChatService chatService;
    private final KnowledgeFileService knowledgeFileService;
    private final LoginUserSession loginUserSession;

    // ------------------------------------------------------------------
    // A. 세션 목록
    // ------------------------------------------------------------------

    /**
     * 세션 목록 조회. 전체 경로: GET /user/rag/sessions
     *
     * @param page 페이지 번호(기본 1)
     * @param rows 페이지 크기(기본 100)
     * @return {@link SessionListResponse} (rows: sessionId/title/createDate, create_date DESC)
     */
    @GetMapping(value = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionListResponse sessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int rows)
    {
        HashMap<String, Object> param = new HashMap<>();

        /* 로그인 사용자 조건 — count 와 목록이 같은 조건을 봐야 하므로 countSessions 보다 먼저 넣는다.
           (이전에는 count 이후에 넣어 총건수만 전체 세션을 세고 있었다.) */
        param.put("chatOwnerUserId", loginUserSession.requireUserName());

        int totalRecords = chatService.countSessions(param);
        int totalPages   = totalRecords == 0 ? 0 : (int) Math.ceil((double) totalRecords / rows);

        // 페이징 사용 시 startRow/pageSize 를 반드시 함께 세팅 (mapper LIMIT/OFFSET 가드 대응)
        param.put("startRow", (page - 1) * rows);
        param.put("pageSize", rows);

        List<ChatMasterDTO> list = chatService.selectSessions(param);
        List<SessionRow> sessionRows = list.stream()
                .map(m -> SessionRow.builder()
                        .sessionId(m.getChatSessionId())
                        .title(m.getChatTitleName())
                        .createDate(m.getCreateDate())
                        .build())
                .collect(Collectors.toList());

        return SessionListResponse.builder()
                .page(page)
                .total(totalPages)
                .records(totalRecords)
                .rows(sessionRows)
                .build();
    }

    // ------------------------------------------------------------------
    // B. 세션 생성
    // ------------------------------------------------------------------

    /**
     * 새 세션 생성. 전체 경로: POST /user/rag/sessions
     *
     * @param body { "title": "새로운채팅"(선택) } — 없거나 공백이면 서버가 "새로운채팅" 기본값
     * @return {@link SessionCreateResponse}
     */
    @PostMapping(value = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionCreateResponse createSession(@RequestBody(required = false) Map<String, Object> body)
    {
        try
        {
            Object title = body == null ? null : body.get("title");
            /* chat_owner_user_id 에 세션 사용자명을 찍어야 생성 직후 목록 조회 조건에 걸린다. */
            ChatMasterDTO created = chatService.createSession(
                    title == null ? null : title.toString(), loginUserSession.requireUserName());
            return SessionCreateResponse.builder()
                    .success(true)
                    .sessionId(created.getChatSessionId())
                    .title(created.getChatTitleName())
                    .build();
        }
        catch (Exception e)
        {
            log.error("세션 생성 실패", e);
            return SessionCreateResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // C. 세션 대화 복원
    // ------------------------------------------------------------------

    /**
     * 세션 대화 복원. 전체 경로: GET /user/rag/sessions/{sessionId}/messages
     *
     * @param sessionId 조회할 세션 ID
     * @return {@link MessageListResponse} (rows: role/content/model?/promptTokens?/completionTokens?/createDate, chat_content_id ASC)
     */
    @GetMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageListResponse messages(@PathVariable String sessionId)
    {
        /* 소유 세션이 아니면 빈 목록 — chat_master 소유권으로 chat_detail 접근을 막는다. */
        List<ChatDetailDTO> list = chatService.selectMessages(sessionId, loginUserSession.requireUserName());
        List<MessageRow> messageRows = list.stream()
                .map(d -> MessageRow.builder()
                        .role(d.getRole())
                        .content(d.getChatContent())
                        .model(d.getModel())
                        .promptTokens(d.getPromptTokens())
                        .completionTokens(d.getCompletionTokens())
                        .createDate(d.getCreateDate())
                        .build())
                .collect(Collectors.toList());

        return MessageListResponse.builder()
                .sessionId(sessionId)
                .rows(messageRows)
                .build();
    }

    // ------------------------------------------------------------------
    // D. RAG 답변 스트리밍 + 대화 저장 (기존 확장)
    // ------------------------------------------------------------------

    /**
     * RAG 답변 스트리밍(SSE). 전체 경로: POST /user/rag/docs
     * 각 답변 토큰을 text/event-stream 의 data 이벤트로 전송한다(기존 동작 유지).
     * <p>{@code sessionId} 가 있으면: 스트림 시작 전 user 메시지 저장, 스트림 완료 후 assistant
     * 메시지(모델명/토큰) 저장. 블로킹 JDBC 저장은 {@link Schedulers#boundedElastic()} 로 오프로딩.
     * 저장 실패는 로그만 남기고 스트림은 정상 종료(사용자 경험 우선).
     *
     * @param body { "query": "질문", "categoryId": "카테고리 UUID(선택)", "sessionId": "세션 ID(선택)" }
     * @return 답변 토큰 스트림
     */
    @PostMapping(value = "/docs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getDocs(@RequestBody Map<String, Object> body)
    {
        String query      = str(body, "query");
        String categoryId = str(body, "categoryId");
        String sessionId  = str(body, "sessionId");

        /* 세션 사용자명은 반드시 여기(요청 스레드)에서 꺼낸다.
           아래 저장 람다는 boundedElastic 스레드에서 돌아 요청 컨텍스트가 없다. */
        String userName = loginUserSession.requireUserName();

        if (query == null || query.isBlank())
        {
            return Flux.just("질문을 입력해 주세요.");
        }

        boolean hasSession = sessionId != null && !sessionId.isBlank();

        // 스트림 동안 서버가 답변 텍스트/메타데이터를 누적
        StringBuilder answer = new StringBuilder();
        AtomicReference<String>  model            = new AtomicReference<>(null);
        AtomicReference<Integer> promptTokens     = new AtomicReference<>(null);
        AtomicReference<Integer> completionTokens = new AtomicReference<>(null);

        Flux<String> textStream = aiService.streamChat(query, categoryId, userName)
                .doOnNext(resp -> captureMetadata(resp.getMetadata(), model, promptTokens, completionTokens))
                .map(AIService::extractText)
                .filter(text -> !text.isEmpty())
                // LLM 호출 예외(가드레일 차단 400 등)를 텍스트 1건으로 바꿔 스트림을 정상 종료시킨다.
                // answer 누적보다 앞에 두어 거부/오류 메시지도 assistant 메시지로 저장되게 한다.
                .onErrorResume(AIRestController::toUserMessage)
                .doOnNext(answer::append);

        if (!hasSession)
        {
            // 방어적: sessionId 누락 시 저장 없이 답변만 스트리밍
            return textStream;
        }

        // user 저장(스트림 시작 전, 독립 트랜잭션) — 블로킹이므로 boundedElastic 오프로딩
        Mono<Void> saveUser = Mono.<Void>fromRunnable(() -> {
            try
            {
                chatService.saveUserMessage(sessionId, query, userName);
            }
            catch (Exception e)
            {
                log.error("user 메시지 저장 실패 (sessionId={})", sessionId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic());

        // assistant 저장 + 세션 제목 자동 변경(스트림 완료 후, 독립 트랜잭션) — 텍스트 스트림 뒤에 이어 붙임
        Flux<String> saveAssistantTail = Mono.<Void>fromRunnable(() -> {
            try
            {
                chatService.saveAssistantMessage(sessionId, answer.toString(),
                        model.get(), promptTokens.get(), completionTokens.get(), userName);
            }
            catch (Exception e)
            {
                log.error("assistant 메시지 저장 실패 (sessionId={})", sessionId, e);
            }
            // 첫 질문 기반 세션 제목 자동 변경(제목이 기본값일 때만 1회). 실패해도 스트림 영향 없음.
            autoRenameTitle(sessionId, query, userName);
        }).subscribeOn(Schedulers.boundedElastic()).thenMany(Flux.<String>empty());

        return saveUser.thenMany(textStream).concatWith(saveAssistantTail);
    }

    /**
     * 세션 제목을 첫 질문 요약으로 자동 변경한다(제목이 기본값 "새로운채팅"일 때만 1회).
     * <p>불필요한 LLM 호출을 피하기 위해 선(先) 제목 확인 후 요약을 수행하고,
     * 실제 갱신은 트랜잭션 내에서 기본값을 재확인해 반영한다(레이스 안전).
     * 모든 실패는 로그만 남기고 스트림/응답에 영향을 주지 않는다.
     */
    private void autoRenameTitle(String sessionId, String query, String userName)
    {
        try
        {
            ChatMasterDTO session = chatService.selectSession(sessionId, userName);
            if (session == null || !ChatService.DEFAULT_TITLE.equals(session.getChatTitleName()))
            {
                // 세션 없음/소유자 아님 or 이미 수동 변경됨 → LLM 호출 없이 종료
                return;
            }
            String title = aiService.summarizeTitle(query);
            chatService.autoRenameTitleIfDefault(sessionId, title, userName);
        }
        catch (Exception e)
        {
            log.error("세션 제목 자동 변경 실패 (sessionId={})", sessionId, e);
        }
    }

    /** LiteLLM 가드레일 차단 응답(400) 본문에서 흔히 관찰되는 표식 — 설정 오류 400 과 구분하는 데 사용. */
    private static final List<String> GUARDRAIL_MARKERS =
            List.of("guardrail", "violated", "unsafe", "content safety", "blocked", "moderation");

    /** 가드레일 차단 시 사용자에게 노출할 거부 메시지. */
    private static final String BLOCKED_MESSAGE =
            "죄송합니다. 요청하신 내용은 안전 정책에 따라 답변할 수 없습니다.";

    /** 그 외 LLM 호출 실패 시 노출할 안내 메시지. */
    private static final String ERROR_MESSAGE =
            "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    /**
     * LLM 호출 예외를 사용자에게 보여줄 텍스트 1건으로 변환한다.
     *
     * <p>이 엔드포인트는 {@code text/event-stream} 이라 예외를 그대로 흘리면 응답 커밋 전에
     * DispatcherServlet 예외 경로로 빠져 HTTP 500 이 되고, Accept 협상 실패
     * ({@code HttpMediaTypeNotAcceptableException})로 에러 본문조차 만들 수 없다.
     * 따라서 모든 실패를 텍스트 방출 + 정상 종료(200)로 바꾼다.
     *
     * <p>가드레일 판정은 LiteLLM 이 수행하며, 차단 시 {@code 400 Bad Request} 로 응답한다.
     * 모델명 오타·컨텍스트 초과 등도 400 이므로 응답 본문의 표식으로 구분하고,
     * 구분에 관계없이 본문 전체를 로그로 남겨 조건을 좁힐 수 있게 한다.
     */
    private static Flux<String> toUserMessage(Throwable e)
    {
        WebClientResponseException wcre = findWebClientException(e);

        if (wcre != null && wcre.getStatusCode().value() == 400)
        {
            String body = wcre.getResponseBodyAsString();
            String normalized = body.toLowerCase(Locale.ROOT);

            if (GUARDRAIL_MARKERS.stream().anyMatch(normalized::contains))
            {
                log.info("가드레일 차단(LiteLLM 400) — body={}", body);
                return Flux.just(BLOCKED_MESSAGE);
            }

            // 가드레일 표식이 없는 400 — 모델명/요청 파라미터 설정 오류일 가능성이 높다.
            log.error("LLM 요청 거부(400, 가드레일 표식 없음) — body={}", body);
            return Flux.just(ERROR_MESSAGE);
        }

        log.error("RAG 답변 생성 실패", e);
        return Flux.just(ERROR_MESSAGE);
    }

    /** 예외 원인 체인에서 {@link WebClientResponseException} 을 찾는다(래핑된 경우 대비). 없으면 null. */
    private static WebClientResponseException findWebClientException(Throwable e)
    {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause())
        {
            if (t instanceof WebClientResponseException wcre)
            {
                return wcre;
            }
        }
        return null;
    }

    /** 마지막 청크 등에서 model/usage 를 확보. 값이 있을 때만 덮어쓴다. */
    private static void captureMetadata(ChatResponseMetadata metadata,
                                        AtomicReference<String> model,
                                        AtomicReference<Integer> promptTokens,
                                        AtomicReference<Integer> completionTokens)
    {
        if (metadata == null)
        {
            return;
        }
        String m = metadata.getModel();
        if (m != null && !m.isBlank())
        {
            model.set(m);
        }
        Usage usage = metadata.getUsage();
        if (usage != null)
        {
            if (usage.getPromptTokens() != null)
            {
                promptTokens.set(usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null)
            {
                completionTokens.set(usage.getCompletionTokens());
            }
        }
    }

    private static String str(Map<String, Object> body, String key)
    {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 지식파일 업로드(인덱싱). 전체 경로: POST /user/rag/embedding
     * 업로드 파일을 청킹·임베딩하여 pgvector(vector_store)에 적재하고,
     * 원본 파일을 디스크에 저장한 뒤 knowledge_files 테이블에 메타 정보를 기록한다.
     *
     * @param file       업로드된 plain text(UTF-8) 파일 (필수)
     * @param categoryId 지식파일이 속한 카테고리 ID — metadata.source 및 knowledge_files.category_id (필수)
     * @return 적재 결과 EmbeddingResponse (성공/실패 모두 HTTP 200 JSON)
     */
    @PostMapping(value = "/embedding",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public EmbeddingResponse embedding(@RequestParam("file") MultipartFile file,
                                       @RequestParam("categoryId") String categoryId)
    {
        /* create_user_id 에 세션 사용자명을 찍어야 업로드 직후 지식파일 목록 조회 조건에 걸린다. */
        return knowledgeFileService.upload(file, categoryId, loginUserSession.requireUserName());
    }
}
