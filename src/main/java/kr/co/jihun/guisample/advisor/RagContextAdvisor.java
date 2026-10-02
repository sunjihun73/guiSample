package kr.co.jihun.guisample.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import kr.co.jihun.guisample.dto.RetrievedChunk;
import kr.co.jihun.guisample.service.HybridRetrievalService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 컨텍스트 주입 어드바이저.
 *
 * <p>사용자 질문으로 하이브리드 검색(dense pgvector + BM25 키워드, RRF 융합)을 수행하고,
 * 검색된 문서 본문을 시스템 프롬프트의 {@code <context>} 블록에 주입한 뒤 다음
 * 어드바이저/모델로 전달한다. 검색 자체는 {@link HybridRetrievalService} 가 맡지만
 * <b>호출은 여전히 어드바이저 체인 안에서</b> 일어나므로 실행 순서 계약은 order 로 보장된다.
 *
 * <p>동작:
 * <ul>
 *   <li>request 의 마지막 user 메시지 텍스트로 하이브리드 검색 실행.</li>
 *   <li>{@code user_name}: 소유권 필터의 유일한 근거. 요청 컨텍스트에 없으면
 *       <b>전체 검색으로 폴백하지 않고</b> 빈 컨텍스트를 주입한다(타 사용자 문서 노출 방지).</li>
 *   <li>{@code category_id}: 값이 있으면 카테고리 한정 검색.</li>
 *   <li>문서 본문을 {@code CONTEXT_SEPARATOR} 로 join(없으면 {@link #NO_CONTEXT}) 하여 주입.</li>
 *   <li>주입은 {@link String#replace(CharSequence, CharSequence)} 리터럴 치환 —
 *       문서 본문에 중괄호가 있어도 프롬프트 템플릿으로 재파싱되지 않아 안전하다.</li>
 * </ul>
 *
 * <p><b>user 메시지는 원문 그대로 유지</b>한다. 시스템 메시지만 증강한다.
 * 스트리밍 경로의 블로킹 검색은 {@link Schedulers#boundedElastic()} 로 오프로딩하므로,
 * 이 클래스와 그 아래 호출 경로는 요청 스레드 바인딩 객체({@code LoginUserSession})를
 * 절대 만지면 안 된다.
 */
@Slf4j
public class RagContextAdvisor implements CallAdvisor, StreamAdvisor
{
    /** 검색된 컨텍스트가 없을 때 시스템 프롬프트에 주입할 안내 문구. */
    private static final String NO_CONTEXT = "(관련 문서가 없습니다.)";

    /** 여러 문서 본문을 하나의 컨텍스트로 이어붙일 때 사용하는 구분자. */
    private static final String CONTEXT_SEPARATOR = "\n\n---\n\n";

    /** 컨텍스트가 주입될 시스템 프롬프트 내 자리표시자. */
    private static final String CONTEXT_PLACEHOLDER = "{context}";

    /**
     * 시스템 프롬프트. {@value #CONTEXT_PLACEHOLDER} 자리에 검색된 문서 본문이 리터럴 치환된다.
     * 리터럴 치환이므로 문서에 중괄호가 있어도 재파싱되지 않아 안전하다.
     */
    private static final String SYSTEM_PROMPT = """
            당신은 사내 문서 기반 도우미입니다.
            아래 <context></context> 안의 내용만 근거로 한국어로 간결하고 정확하게 답하세요.
            사용자가 질문 문장이 아니라 단어나 이름만 입력했다면, 그 대상에 대해 컨텍스트가 말하는
            내용을 모아 설명하세요. 직접적인 정의문이 없더라도 컨텍스트에 드러난 사실로 설명하면 됩니다.
            "제공된 문서에서 관련 내용을 찾지 못했습니다." 는 컨텍스트에 그 대상이 실제로 등장하지
            않을 때만 쓰세요. 컨텍스트 밖의 지식으로 추측하지는 마세요.
            <context>
            {context}
            </context>
            """;

    /** dense + BM25 하이브리드 검색기. */
    private final HybridRetrievalService hybridRetrievalService;

    /** 검색 튜닝 파라미터. 각 값의 실측 근거는 {@code SpringAiConfig} javadoc 참조. */
    private final RagRetrievalSettings settings;

    /** 어드바이저 실행 순서(작을수록 먼저). */
    private final int order;

    public RagContextAdvisor(HybridRetrievalService hybridRetrievalService,
                             RagRetrievalSettings settings, int order)
    {
        this.hybridRetrievalService = hybridRetrievalService;
        this.settings               = settings;
        this.order                  = order;
    }

    @Override
    public String getName()
    {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder()
    {
        return this.order;
    }

    // ------------------------------------------------------------------
    // 동기(.call()) 경로 — 벡터 검색 후 컨텍스트 주입한 요청으로 다음 어드바이저 진행
    // ------------------------------------------------------------------
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain)
    {
        return chain.nextCall(augmentWithContext(request));
    }

    // ------------------------------------------------------------------
    // 스트리밍(.stream()) 경로 — 블로킹 벡터 검색을 boundedElastic 로 오프로딩 후 진행
    // ------------------------------------------------------------------
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain)
    {
        return Mono.fromCallable(() -> augmentWithContext(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(chain::nextStream);
    }

    /**
     * 요청의 user 질문으로 벡터 검색을 수행하고, 검색된 컨텍스트를 시스템 메시지에 주입한 새 요청을 만든다.
     * user 메시지는 변경하지 않으며 시스템 메시지만 증강한다. 질문이 공백이면 원 요청을 그대로 반환한다.
     */
    private ChatClientRequest augmentWithContext(ChatClientRequest request)
    {
        UserMessage userMessage = request.prompt().getUserMessage();
        String userText = userMessage == null ? null : userMessage.getText();
        if (userText == null || userText.isBlank())
        {
            return request;
        }

        String context = retrieveContext(userText,
                request.context().get("category_id"),
                request.context().get("user_name"));
        String systemText = SYSTEM_PROMPT.replace(CONTEXT_PLACEHOLDER, context);

        // 시스템 메시지만 증강(기존 시스템 메시지가 없으면 새로 추가), user 메시지는 원문 유지.
        Prompt augmentedPrompt = request.prompt().augmentSystemMessage(systemText);
        return request.mutate().prompt(augmentedPrompt).build();
    }

    /**
     * 하이브리드 검색을 수행해 컨텍스트 문자열을 구성한다.
     *
     * @param query      검색 질의(원문 user 텍스트)
     * @param categoryId 요청 컨텍스트의 category_id (null/공백이면 전체 검색)
     * @param userName   요청 컨텍스트의 user_name (소유권 필터 기준, 필수)
     * @return 문서 본문을 join 한 컨텍스트(결과가 없으면 {@link #NO_CONTEXT})
     */
    private String retrieveContext(String query, Object categoryId, Object userName)
    {
        if (userName == null || userName.toString().isBlank())
        {
            // 소유권 근거가 없으면 전체 검색으로 흘려보내지 않는다(타 사용자 문서 노출 방지).
            // 단 SSE 경로이므로 예외는 던지지 않고 컨텍스트만 비운다.
            log.warn("요청 컨텍스트에 user_name 이 없어 RAG 검색을 건너뛴다.");
            return NO_CONTEXT;
        }

        List<RetrievedChunk> chunks = hybridRetrievalService.retrieve(
                query,
                categoryId == null ? null : categoryId.toString(),
                userName.toString(),
                settings);

        if (chunks.isEmpty())
        {
            return NO_CONTEXT;
        }
        return chunks.stream()
                .map(RetrievedChunk::text)
                .collect(Collectors.joining(CONTEXT_SEPARATOR));
    }
}
