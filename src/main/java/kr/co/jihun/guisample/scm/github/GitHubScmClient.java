package kr.co.jihun.guisample.scm.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmEntryType;
import kr.co.jihun.guisample.scm.ScmException;
import kr.co.jihun.guisample.scm.ScmPushResult;
import kr.co.jihun.guisample.scm.ScmTree;
import kr.co.jihun.guisample.scm.ScmTreeEntry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST API 기반 {@link ScmClient}.
 *
 * <p>라이브러리(hub4j github-api) 대신 Spring {@link RestClient} 로 직접 호출한다 — 쓰는 API 가
 * 커밋 목록·커밋 상세·파일 내용(Contents)·저장소 메타·브랜치·트리(Git Trees) 몇 개뿐이고, 라이브러리는 Jackson 2 와 자체 HTTP 스택을
 * 끌고 들어와 Boot 4(Jackson 3) 환경과 겹친다.
 */
@Component
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubScmClient implements ScmClient
{
    private static final int PER_PAGE = 100;

    /** Git 트리에서 심볼릭 링크를 나타내는 blob mode. */
    private static final String SYMLINK_MODE = "120000";

    /** 파일 경로 구분자 — Contents API 경로를 세그먼트로 쪼갤 때 쓴다. */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("/");

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

    /**
     * Contents API 로 파일 하나를 기본 브랜치에 커밋한다.
     *
     * <p>생성과 교체가 같은 엔드포인트(PUT)이고, <b>교체일 때만</b> 기존 blob 의 sha 가 필요하다.
     * 그래서 먼저 GET 으로 sha 를 읽고(없으면 404 → 신규 생성), PUT 에 실어 보낸다.
     * sha 를 빼고 기존 파일에 PUT 하면 GitHub 가 422 로 거절한다.
     *
     * <p>토큰에 <b>contents:write</b> 권한이 필요하다 — 읽기만 가능한 토큰이면 403 이다.
     */
    @Override
    public ScmPushResult putFile(String repoUrl, String path, String content, String commitMessage)
    {
        RepoRef ref = require(repoUrl);
        String existingSha = findFileSha(ref, path);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", commitMessage);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (existingSha != null)
        {
            body.put("sha", existingSha);
        }

        ContentCommitResponse response = call(repoUrl, () -> restClient.put()
                .uri(uri -> contentsUri(uri, ref, path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ContentCommitResponse.class));

        String commitId = (response == null || response.commit() == null) ? null : response.commit().sha();
        return new ScmPushResult(path, commitId, existingSha == null);
    }

    /**
     * 브랜치 최신 커밋의 전체 파일 트리 — GitHub 호출 3회.
     *
     * <ol>
     *   <li>{@code GET /repos/{o}/{r}} — 기본 브랜치. 404 면 "저장소를 찾을 수 없음"</li>
     *   <li>{@code GET /repos/{o}/{r}/branches/{branch}} — 최신 커밋(sha·메시지·시각)과 트리 sha.
     *       404 면 "브랜치를 찾을 수 없음" — 1)을 먼저 부르는 이유가 저장소 없음과 이 경우를 구분하기 위해서다</li>
     *   <li>{@code GET /repos/{o}/{r}/git/trees/{treeSha}?recursive=1} — 전체 항목</li>
     * </ol>
     *
     * <p>base URL 은 설정값({@code scm.github.api-base-url})으로 고정이라 사용자 입력이 호출 대상 호스트를 바꿀 수 없다.
     */
    @Override
    public ScmTree findTree(String repoUrl, String branch)
    {
        RepoRef ref = require(repoUrl);

        RepoMeta meta = call(repoUrl, () -> restClient.get()
                .uri(uri -> uri.path("/repos/{owner}/{repo}").build(ref.owner(), ref.repo()))
                .retrieve()
                .body(RepoMeta.class));
        String defaultBranch = meta == null ? null : meta.defaultBranch();

        boolean explicitBranch = StringUtils.hasText(branch);
        String targetBranch = explicitBranch ? branch.trim() : defaultBranch;
        if (!StringUtils.hasText(targetBranch))
        {
            throw new ScmException("커밋이 없는 빈 저장소이거나 브랜치가 없습니다: " + repoUrl);
        }

        BranchInfo branchInfo = findBranch(repoUrl, ref, targetBranch, explicitBranch);
        BranchCommit head = branchInfo == null ? null : branchInfo.commit();
        if (head == null || head.commit() == null || head.commit().tree() == null
            || !StringUtils.hasText(head.commit().tree().sha()))
        {
            throw new ScmException("브랜치의 최신 커밋 정보를 읽지 못했습니다: " + repoUrl);
        }
        String commitSha = head.sha();
        String treeSha   = head.commit().tree().sha();

        TreeResponse tree = call(repoUrl, () -> restClient.get()
                .uri(uri -> uri.path("/repos/{owner}/{repo}/git/trees/{treeSha}")
                        .queryParam("recursive", 1)
                        .build(ref.owner(), ref.repo(), treeSha))
                .retrieve()
                .body(TreeResponse.class));

        List<ScmTreeEntry> entries = new ArrayList<>();
        if (tree != null && tree.tree() != null)
        {
            for (TreeItem item : tree.tree())
            {
                ScmTreeEntry entry = toEntry(item, properties.webHost(), ref, commitSha);
                if (entry != null)
                {
                    entries.add(entry);
                }
            }
        }

        String branchName = (branchInfo.name() != null) ? branchInfo.name() : targetBranch;
        CommitData data = head.commit();
        Instant commitDate = data.committer() == null ? null : parseInstant(data.committer().date());
        return new ScmTree(branchName, defaultBranch, commitSha, data.message() == null ? "" : data.message(),
                commitDate, tree != null && tree.truncated(), entries);
    }

    /** 브랜치 조회 — 404/409 를 브랜치·빈 저장소 문구로 바꾼다. 나머지 오류는 공통 문구. */
    private BranchInfo findBranch(String repoUrl, RepoRef ref, String branch, boolean explicitBranch)
    {
        try
        {
            return restClient.get()
                    .uri(uri -> branchUri(uri, ref, branch))
                    .retrieve()
                    .body(BranchInfo.class);
        }
        catch (RestClientResponseException e)
        {
            int status = e.getStatusCode().value();
            if (status == 404 && explicitBranch)
            {
                throw new ScmException("브랜치 '" + branch + "' 를 찾을 수 없습니다: " + repoUrl, e);
            }
            if (status == 404 || status == 409)
            {
                throw new ScmException("커밋이 없는 빈 저장소이거나 브랜치가 없습니다: " + repoUrl, e);
            }
            throw toScmException(repoUrl, e);
        }
        catch (RestClientException e)
        {
            throw new ScmException("GitHub 호출 실패: " + repoUrl + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Branches API URI — 슬래시가 든 브랜치명({@code feature/x})을 세그먼트로 쪼개 넣는다
     * ({@link #contentsUri} 와 같은 관례). 세그먼트는 URI 변수 값으로 넘겨 브랜치명의 중괄호 등이
     * 템플릿으로 해석되지 않게 한다.
     */
    static URI branchUri(UriBuilder uri, RepoRef ref, String branch)
    {
        String[] segments = PATH_SEPARATOR.split(branch, -1);
        List<Object> values = new ArrayList<>(List.of(ref.owner(), ref.repo()));
        uri.pathSegment("repos", "{owner}", "{repo}", "branches");
        for (int i = 0; i < segments.length; i++)
        {
            /* '.'·'..' 는 Git 브랜치명에 올 수 없고, URI 정규화로 다른 API 경로를 가리킬 수 있어 거부한다. */
            if (segments[i].isEmpty() || ".".equals(segments[i]) || "..".equals(segments[i]))
            {
                throw new ScmException("브랜치명이 올바르지 않습니다: " + branch);
            }
            uri.pathSegment("{b" + i + "}");
            values.add(segments[i]);
        }
        return uri.build(values.toArray());
    }

    /** GitHub 트리 항목 종류 → 저장소 중립 종류. 알 수 없는 종류면 null. */
    static ScmEntryType entryType(String type, String mode)
    {
        if (type == null)
        {
            return null;
        }
        return switch (type)
        {
            case "tree" -> ScmEntryType.DIR;
            case "blob" -> SYMLINK_MODE.equals(mode) ? ScmEntryType.SYMLINK : ScmEntryType.FILE;
            case "commit" -> ScmEntryType.SUBMODULE;
            default -> null;
        };
    }

    /** 트리 항목 → {@link ScmTreeEntry}. 경로가 없거나 종류를 모르면 null(목록에서 뺀다). size 는 FILE 만. */
    static ScmTreeEntry toEntry(TreeItem item, String webHost, RepoRef ref, String commitSha)
    {
        ScmEntryType type = item == null ? null : entryType(item.type(), item.mode());
        if (type == null || !StringUtils.hasText(item.path()))
        {
            return null;
        }
        Long size = type == ScmEntryType.FILE ? item.size() : null;
        return new ScmTreeEntry(item.path(), type, size, webUrl(webHost, ref, commitSha, item.path(), type));
    }

    /**
     * GitHub 웹 화면 주소 — 폴더는 {@code tree}, 파일·링크는 {@code blob}, 서브모듈은 null.
     * 커밋 SHA 로 고정해 브랜치가 움직여도 화면과 같은 내용을 본다. 경로 세그먼트는 각각 인코딩한다(슬래시 유지).
     */
    static String webUrl(String webHost, RepoRef ref, String commitSha, String path, ScmEntryType type)
    {
        if (type == ScmEntryType.SUBMODULE || !StringUtils.hasText(commitSha))
        {
            return null;
        }
        String kind = type == ScmEntryType.DIR ? "tree" : "blob";
        return UriComponentsBuilder.newInstance()
                .scheme("https")
                .host(webHost)
                .pathSegment(ref.owner(), ref.repo(), kind, commitSha)
                .pathSegment(PATH_SEPARATOR.split(path))
                .build()
                .encode()
                .toUriString();
    }

    private static Instant parseInstant(String value)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        try
        {
            return Instant.parse(value);
        }
        catch (DateTimeParseException e)
        {
            return null;    // 표시용 값이라 목록 조회를 실패시키지 않는다
        }
    }

    /** 기존 파일의 blob sha. 파일이 없으면(404) null — 디렉터리이거나 경로가 비정상이면 예외. */
    private String findFileSha(RepoRef ref, String path)
    {
        try
        {
            ContentItem item = restClient.get()
                    .uri(uri -> contentsUri(uri, ref, path))
                    .retrieve()
                    .body(ContentItem.class);
            return item == null ? null : item.sha();
        }
        catch (RestClientResponseException e)
        {
            if (e.getStatusCode().value() == 404)
            {
                return null;    // 아직 없는 파일 — 신규 생성
            }
            throw toScmException(ref.toUrl(), e);
        }
        catch (RestClientException e)
        {
            throw new ScmException("GitHub 호출 실패: " + ref.toUrl() + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Contents API URI — 경로를 <b>세그먼트 단위로</b> 넣는다.
     *
     * <p>{@code .../contents/{path}} 처럼 URI 변수 하나로 넘기면 RestClient 의 인코딩
     * (TEMPLATE_AND_VALUES)이 슬래시를 {@code %2F} 로 바꿔 GitHub 가 404 를 준다.
     * 세그먼트로 쪼개 넣으면 구분자가 그대로 남는다.
     */
    static URI contentsUri(UriBuilder uri, RepoRef ref, String path)
    {
        String[] segments = PATH_SEPARATOR.split(path.startsWith("/") ? path.substring(1) : path);
        for (String segment : segments)
        {
            if (segment.isEmpty())
            {
                throw new ScmException("파일 경로가 올바르지 않습니다: " + path);
            }
        }
        return uri.pathSegment("repos", ref.owner(), ref.repo(), "contents")
                .pathSegment(segments)
                .build();
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
            throw toScmException(repoUrl, e);
        }
        catch (RestClientException e)
        {
            throw new ScmException("GitHub 호출 실패: " + repoUrl + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * HTTP 오류 → 사용자가 읽을 수 있는 {@link ScmException}.
     *
     * <p>401/403 은 <b>토큰이 설정됐는지</b>로 문구를 나눈다. 공개 저장소는 토큰 없이도 조회(REPO동기화)가
     * 되지만 쓰기(REPO PUSH)는 401 이라, 토큰 미설정이 "토큰 확인 필요" 로만 보이면 설정이 비어 있다는
     * 사실이 드러나지 않는다.
     */
    private ScmException toScmException(String repoUrl, RestClientResponseException e)
    {
        boolean hasToken = StringUtils.hasText(properties.token());
        int status = e.getStatusCode().value();
        String reason = switch (status)
        {
            case 401 -> hasToken
                    ? "GitHub 인증 실패 (토큰이 만료됐거나 잘못됨)"
                    : "GitHub 인증 실패 — 토큰이 설정되지 않았습니다 (.env 의 GITHUB_TOKEN). "
                      + "공개 저장소 조회는 토큰 없이 되지만 푸시는 contents:write 권한이 있는 토큰이 필요합니다";
            case 403, 429 -> hasToken
                    ? "GitHub 접근 거부 또는 호출 한도 초과 (푸시에는 contents:write 권한이 필요하다)"
                    : "GitHub 접근 거부 또는 호출 한도 초과 (토큰 미설정 — 미인증 호출은 시간당 60회)";
            case 404 -> "저장소를 찾을 수 없음 (비공개 저장소면 토큰 필요)";
            case 409 -> "커밋 충돌 또는 빈 저장소";
            case 422 -> "GitHub 가 요청을 거절함 (경로·브랜치 확인 필요)";
            default -> "GitHub 응답 오류 " + status;
        };
        return new ScmException(reason + ": " + repoUrl, e);
    }

    record RepoRef(String host, String owner, String repo)
    {
        /** 오류 메시지에 쓸 저장소 표기. */
        String toUrl()
        {
            return "https://" + host + "/" + owner + "/" + repo;
        }
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

    /** Contents API 단건 응답 — 필요한 건 blob sha 뿐이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentItem(String sha)
    {
    }

    /** Contents API PUT 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentCommitResponse(ContentItem content, CommitRef commit)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitRef(String sha)
    {
    }

    /** 저장소 메타 — 필요한 건 기본 브랜치뿐이다. (Jackson 에는 MyBatis 의 camelCase 매핑이 없어 이름을 지정한다.) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepoMeta(@JsonProperty("default_branch") String defaultBranch)
    {
    }

    /** Branches API 단건 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BranchInfo(String name, BranchCommit commit)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BranchCommit(String sha, CommitData commit)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitData(String message, GitActor committer, TreeRef tree)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitActor(String date)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TreeRef(String sha)
    {
    }

    /** Git Trees API 응답 ({@code recursive=1}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TreeResponse(String sha, List<TreeItem> tree, boolean truncated)
    {
    }

    /** 트리 항목 — size 는 blob 만 온다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TreeItem(String path, String mode, String type, String sha, Long size)
    {
    }
}
