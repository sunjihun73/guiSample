package kr.co.jihun.guisample.scm.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 연동 설정 ({@code scm.github.*}).
 *
 * @param apiBaseUrl REST API 루트. GitHub Enterprise 면 {@code https://<host>/api/v3}
 * @param webHost    저장소 URL 의 호스트 — 이 호스트의 URL 만 GitHub 구현이 맡는다
 * @param token      개인 액세스 토큰. 비어 있으면 비인증 호출(공개 저장소만, 시간당 60회)
 * @param maxPages   커밋 목록 최대 페이지 수(페이지당 100건) — 오래된 대형 저장소에서 무한히 돌지 않게 막는다
 */
@ConfigurationProperties("scm.github")
public record GitHubProperties(String apiBaseUrl, String webHost, String token, int maxPages)
{
}
