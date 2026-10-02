package kr.co.jihun.guisample.config;

import kr.co.jihun.guisample.advisor.RagContextAdvisor;
import kr.co.jihun.guisample.advisor.RagRetrievalSettings;
import kr.co.jihun.guisample.service.HybridRetrievalService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring AI ChatClient 구성.
 * spring-ai-starter-model-openai 가 자동 구성한 {@link ChatClient.Builder}(OpenAI gpt-4o-mini)
 * 로부터 애플리케이션 전역에서 재사용할 RAG ChatClient 빈을 만든다.
 */
@Configuration
public class SpringAiConfig
{
    /** RAG 답변 생성용 ChatClient (OpenAI gpt-4o-mini). */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder)
    {
        return builder.build();
    }

    /** 최종 컨텍스트로 주입할 청크 수. */
    private static final int RAG_TOP_K = 8;

    /** dense/keyword 각 검색기에서 가져올 후보 수. */
    private static final int RAG_CANDIDATE_K = 20;

    /** RRF 상수 k. 표준값 60. 순위만 쓰므로 두 검색기의 점수 스케일이 달라도 무방하다. */
    private static final int RAG_RRF_K = 60;

    /**
     * dense 후보 편입 하한 (코사인).
     *
     * <p>2026-09-17 코퍼스(318 청크, text-embedding-3-large@1536) 실측: 무관 문서 상한
     * 0.247 / 가장 약한 정답 0.356. 0.25 는 노이즈 밴드 바로 위로, 융합에 충분한 recall
     * 목록을 준다. 여기서 편입된 노이즈가 dense 20위여도 RRF 기여는 {@code 1/80} 에 불과해
     * 두 검색기가 합의한 문서를 밀어낼 수 없다.
     *
     * <p><b>임베딩 모델 종속 값이다.</b> 모델을 바꾸면 반드시 재측정할 것 —
     * {@code src/docs/measurements.md} 에 측정 절차와 이력이 있다.
     */
    private static final double RAG_CANDIDATE_SIMILARITY = 0.25;

    /**
     * "관련 문서 없음" 판정 하한 (코사인). dense 결과에 이 값 이상이 하나도 없으면
     * 컨텍스트를 비워 시스템 프롬프트가 "찾지 못했습니다" 로 답하게 한다.
     *
     * <p><b>이 게이트가 없으면 안 된다.</b> 문자 bigram 특성상 어떤 한국어 질의든 거의 모든
     * 한국어 청크와 bigram 을 공유하므로 BM25 는 무관한 질문("오늘 점심 메뉴")에도 후보를
     * 가득 돌려준다. 융합 점수는 순위 인공물이라 "관련 문서가 있었는가" 정보를 담지 못하므로,
     * 판정은 dense 유사도로 해야 한다.
     *
     * <p>값 0.32 는 하이브리드 도입 전과 <b>수치적으로 동일</b>하다 — 무관 질문의 기존 동작이
     * 그대로 보존된다. 고유명사 케이스(dense 최고 0.372)는 통과하므로 개선 목표에도 지장 없다.
     *
     * <p>단독으로 쓰면 고유명사 질의에서 자책골이 되므로 아래 {@link #RAG_GATE_KEYWORD_NORM} 과
     * OR 로 묶는다.
     */
    private static final double RAG_GATE_SIMILARITY = 0.32;

    /**
     * 같은 판정의 keyword 하한 — <b>질의 bigram 수로 정규화한</b> BM25 점수.
     *
     * <p>2026-09-17 실측(정규화 점수): 신호 테오리아 5.28 / 율리안 4.45 / "율리안은 누구인가" 2.42,
     * 노이즈 김치찌개 1.19 / 파이썬 리스트 정렬 0.86 / 오늘 점심 메뉴 0.71.
     * 노이즈 상한 1.19 와 신호 최저 2.42 사이인 2.0 을 택했다.
     *
     * <p><b>원점수를 쓰면 안 된다.</b> BM25 원점수는 질의가 길수록 항이 많아 커져서, 무관한 긴
     * 질의(7.15)가 정답인 짧은 질의(2.84)보다 높게 나온다. 반드시 정규화 값으로 판정할 것.
     */
    private static final double RAG_GATE_KEYWORD_NORM = 2.0;

    /**
     * RAG 컨텍스트 주입 어드바이저 빈.
     * <p>체인 내 유일한 어드바이저이므로 order 는 최고 우선순위 계열({@code HIGHEST_PRECEDENCE + 100})로 둔다.
     */
    @Bean
    public RagContextAdvisor ragContextAdvisor(HybridRetrievalService hybridRetrievalService)
    {
        RagRetrievalSettings settings = new RagRetrievalSettings(
                RAG_TOP_K, RAG_CANDIDATE_K, RAG_RRF_K,
                RAG_CANDIDATE_SIMILARITY, RAG_GATE_SIMILARITY, RAG_GATE_KEYWORD_NORM);

        return new RagContextAdvisor(
                hybridRetrievalService, settings, Ordered.HIGHEST_PRECEDENCE + 100);
    }
}
