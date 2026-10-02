package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.advisor.RagContextAdvisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Spring AI 기반 RAG 서비스.
 * 어드바이저 체인(RAG 컨텍스트 주입)을 통해 ChatClient(gpt-4o-mini)로 답변을 스트리밍한다.
 * 벡터 검색·컨텍스트 주입은 {@link RagContextAdvisor} 가 담당하고, 이 서비스는 어드바이저 등록과
 * category_id 파라미터 전달만 수행한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AIService
{
    private final ChatClient chatClient;
    /** RAG 컨텍스트 주입 어드바이저 — 벡터 검색으로 컨텍스트를 시스템 메시지에 주입한다. */
    private final RagContextAdvisor ragContextAdvisor;

    /** 자동 제목 요약 프롬프트(순수 요약 — RAG 컨텍스트/어드바이저 미사용). */
    private static final String TITLE_PROMPT = """
            다음 질문을 15자 이내의 짧은 명사형 한국어 제목으로 요약하라. 따옴표·마침표 없이 제목만 출력하라.
            질문: %s
            """;

    /** 요약 제목 저장 상한(컬럼 varchar(200) 안전 절삭). */
    private static final int MAX_TITLE_LEN = 200;

    /** LLM 실패/빈 응답 fallback 시 질문 원문 절삭 길이. */
    private static final int FALLBACK_TITLE_LEN = 20;

    /**
     * 질의에 대한 RAG 답변을 {@link ChatResponse} 단위로 스트리밍한다.
     * 텍스트뿐 아니라 model/usage(토큰) 메타데이터를 확보할 수 있어 대화 저장 경로에서 사용한다.
     *
     * <p>동작(어드바이저 체인):
     * <ol>
     *   <li>{@link RagContextAdvisor}: pgvector 유사도 검색(top-k·유사도 임계값은 SpringAiConfig, category_id 메타 필터)으로
     *       컨텍스트를 시스템 메시지에 주입.</li>
     *   <li>ChatClient 스트림을 {@code .chatResponse()} 로 소비 → Flux&lt;ChatResponse&gt;.</li>
     * </ol>
     * 각 청크에서 {@link #extractText(ChatResponse)} 로 델타 텍스트를, 마지막 청크의
     * {@code getMetadata()} 에서 model/usage 를 얻는다. 질의가 비면 {@link Flux#empty()}.
     *
     * @param query      사용자가 입력한 질문
     * @param categoryId 검색을 한정할 카테고리 ID (null/공백이면 전체 벡터 검색)
     * @param userName   소유권 필터 기준(user_master.user_name). 컨트롤러가 <b>요청 스레드에서</b>
     *                   꺼내 넘겨야 한다 — 어드바이저는 boundedElastic 에서 돌아
     *                   LoginUserSession 에 접근할 수 없다.
     * @return ChatResponse 스트림
     */
    public Flux<ChatResponse> streamChat(String query, String categoryId, String userName)
    {
        if (query == null || query.isBlank())
        {
            return Flux.empty();
        }

        return chatClient.prompt()
                // 어드바이저 체인: RAG 컨텍스트 주입.
                .advisors(ragContextAdvisor)
                // RagContextAdvisor 가 request.context() 로 읽는다. 값이 있을 때만 전달(param 은 null 불가).
                .advisors(spec ->
                {
                    if (categoryId != null && !categoryId.isBlank())
                    {
                        spec.param("category_id", categoryId);
                    }
                    // user_name 은 소유권 필터의 유일한 근거다.
                    // 없으면 어드바이저가 전체 검색으로 폴백하지 않고 컨텍스트를 비운다.
                    if (userName != null && !userName.isBlank())
                    {
                        spec.param("user_name", userName);
                    }
                })
                .user(query)
                .stream()
                .chatResponse();
    }

    /**
     * ChatResponse 청크에서 델타 텍스트를 안전하게 추출한다.
     * 마지막 usage-only 청크 등 result/output 이 null 인 경우 빈 문자열을 반환한다.
     */
    public static String extractText(ChatResponse response)
    {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null)
        {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 사용자 질문을 짧은 한국어 세션 제목으로 요약한다.
     * <p>RAG 어드바이저/벡터 컨텍스트 없이 ChatClient 를 비스트리밍(.call())으로 1회 호출하는
     * 가벼운 요약이다. LLM 호출 실패/빈 응답 시 질문 앞 {@value #FALLBACK_TITLE_LEN}자 절삭으로 fallback.
     * 결과는 varchar(200) 안전을 위해 {@value #MAX_TITLE_LEN}자로 절삭한다.
     *
     * @param question 사용자 질문
     * @return 요약 제목(항상 non-blank, question 이 공백이면 빈 문자열)
     */
    public String summarizeTitle(String question)
    {
        if (question == null || question.isBlank())
        {
            return "";
        }

        String fallback = truncate(question.trim(), FALLBACK_TITLE_LEN);

        try
        {
            String result = chatClient.prompt()
                    .user(String.format(TITLE_PROMPT, question))
                    .call()
                    .content();

            String cleaned = cleanTitle(result);
            return cleaned.isBlank() ? fallback : truncate(cleaned, MAX_TITLE_LEN);
        }
        catch (Exception e)
        {
            log.warn("세션 제목 요약 실패 — 질문 원문 절삭으로 fallback", e);
            return fallback;
        }
    }

    /** 요약 결과에서 앞뒤 공백/따옴표/마침표를 제거한다. */
    private static String cleanTitle(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        // 앞뒤 공백·큰/작은따옴표·마침표 제거, 내부 개행은 공백으로
        String s = raw.replace("\r", " ").replace("\n", " ").trim();
        s = s.replaceAll("^[\"'`.\\s]+", "").replaceAll("[\"'`.\\s]+$", "");
        return s.trim();
    }

    /** 문자 수 기준 절삭(말줄임 없이 원문 절삭). */
    private static String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
