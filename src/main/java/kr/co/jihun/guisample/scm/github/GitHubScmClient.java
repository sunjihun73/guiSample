package kr.co.jihun.guisample.scm.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST API 기반 {@link ScmClient}.
 *
 * <p>라이브러리(hub4j github-api) 대신 Spring {@link RestClient} 로 직접 호출한다 — 필요한 API 가
 * 커밋 목록·커밋 상세 두 개뿐이고, 라이브러리는 Jackson 2 와 자체 HTTP 스택을 끌고 들어와
 * Boot 4(Jackson 3) 환경과 겹친다.
 */
@Component
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubScmClient implements ScmClient
{
    private static final int PER_PAGE = 100;

    /** owner/repo 추출 — https://host/owner/repo(.git)(/...) 와 git@host:owner/repo(.git) 를 받는다. */
    private static final Pattern HTTP_URL = Pattern.compile("^https?://([^/]+)/([^/]+)/([^/?#]+?)(?:\\.git)?(?:[/?#].*)?$");
    private static final Pattern SSH_URL  = Pattern.compile("^git@([^:]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final GitHubProperties properties;
    private final RestClient restClient;

    public GitHubScmClient(GitHubProperties properties)
    {
        this.properties = properties;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)   // 이름이 바뀐 저장소는 301 로 옮겨 준다
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(properties.token()))
        {
            builder.defaultHeader("Authorization", "Bearer " + properties.token());
        }
        this.restClient = builder.build();
    }

    @Override
    public boolean supports(String repoUrl)
    {
        RepoRef ref = parse(repoUrl);
        return ref != null && ref.host().equalsIgnoreCase(properties.webHost());
    }

    @Override
    public List<ScmCommit> findCommitsSince(String repoUrl, Instant since)
    {
        RepoRef ref = require(repoUrl);
        List<ScmCommit> commits = new ArrayList<>();

        for (int page = 1; page <= properties.maxPages(); page++)
        {
            int currentPage = page;
            List<CommitItem> items = call(repoUrl, () -> restClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/commits")
                            .queryParam("since", since.toString())
                            .queryParam("per_page", PER_PAGE)
                            .queryParam("page", currentPage)
                            .build(ref.owner(), ref.repo()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CommitItem>>() {}));

            if (items == null || items.isEmpty())
            {
                break;
            }
            for (CommitItem item : items)
            {
                String message = item.commit() == null ? "" : item.commit().message();
                commits.add(new ScmCommit(item.sha(), message == null ? "" : message));
            }
            if (items.size() < PER_PAGE)
            {
                break;
            }
        }
        return commits;
    }

    @Override
    public List<String> findChangedFiles(String repoUrl, String commitId)
    {
        RepoRef ref = require(repoUrl);
        List<String> files = new ArrayList<>();

        /* 커밋 상세의 files 도 페이지 단위다(최대 3000개). 한 페이지가 꽉 차면 다음 페이지를 읽는다. */
        for (int page = 1; page <= properties.maxPages(); page++)
        {
            int currentPage = page;
            CommitDetail detail = call(repoUrl, () -> restClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/commits/{sha}")
                            .queryParam("per_page", PER_PAGE)
                            .queryParam("page", currentPage)
                            .build(ref.owner(), ref.repo(), commitId))
                    .retrieve()
                    .body(CommitDetail.class));

            if (detail == null || detail.files() == null || detail.files().isEmpty())
            {
                break;
            }
            detail.files().forEach(file -> files.add(file.filename()));
            if (detail.files().size() < PER_PAGE)
            {
                break;
            }
        }
        return files;
    }

    private RepoRef require(String repoUrl)
    {
        RepoRef ref = parse(repoUrl);
        if (ref == null)
        {
            throw new ScmException("GitHub 저장소 URL 형식이 아닙니다: " + repoUrl);
        }
        return ref;
    }

    static RepoRef parse(String repoUrl)
    {
        if (!StringUtils.hasText(repoUrl))
        {
            return null;
        }
        String url = repoUrl.trim();
        Matcher m = HTTP_URL.matcher(url);
        if (!m.matches())
        {
            m = SSH_URL.matcher(url);
            if (!m.matches())
            {
                return null;
            }
        }
        return new RepoRef(m.group(1), m.group(2), m.group(3));
    }

    /** HTTP 오류를 사용자가 읽을 수 있는 {@link ScmException} 으로 바꾼다. */
    private <T> T call(String repoUrl, Supplier<T> request)
    {
        try
        {
            return request.get();
        }
        catch (RestClientResponseException e)
        {
            int status = e.getStatusCode().value();
            String reason = switch (status)
            {
                case 401 -> "GitHub 인증 실패 (토큰 확인 필요)";
                case 403, 429 -> "GitHub 접근 거부 또는 호출 한도 초과";
                case 404 -> "저장소를 찾을 수 없음 (비공개 저장소면 토큰 필요)";
                case 409 -> "빈 저장소";
                default -> "GitHub 응답 오류 " + status;
            };
            throw new ScmException(reason + ": " + repoUrl, e);
        }
        catch (RestClientException e)
        {
            throw new ScmException("GitHub 호출 실패: " + repoUrl + " (" + e.getMessage() + ")", e);
        }
    }

    record RepoRef(String host, String owner, String repo)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitItem(String sha, CommitInfo commit)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitInfo(String message)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitDetail(List<CommitFile> files)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitFile(String filename)
    {
    }
}
