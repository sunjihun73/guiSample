package kr.co.jihun.guisample.scm.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import kr.co.jihun.guisample.scm.ScmArchiveVisitor;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmEntryType;
import kr.co.jihun.guisample.scm.ScmException;
import kr.co.jihun.guisample.scm.ScmPaths;
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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub REST API 기반 {@link ScmClient}.
 *
 * <p>라이브러리(hub4j github-api) 대신 Spring {@link RestClient} 로 직접 호출한다 — 쓰는 API 가
 * 커밋 목록·커밋 상세·파일 내용(Contents)·저장소 메타·브랜치·트리(Git Trees)·아카이브(zipball) 몇 개뿐이고, 라이브러리는 Jackson 2 와 자체 HTTP 스택을
 * 끌고 들어와 Boot 4(Jackson 3) 환경과 겹친다.
 */
@Component
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubScmClient implements ScmClient
{
    private static final int PER_PAGE = 100;

    /** 저장소 압축본(zipball) 상한 — 서버 메모리에 통째로 받으므로 막아 둔다. 설정화하지 않는다(새 yaml 키 누락 위험). */
    static final long MAX_ARCHIVE_BYTES = 50L * 1024 * 1024;

    /** Git 트리에서 심볼릭 링크를 나타내는 blob mode. */
    private static final String SYMLINK_MODE = "120000";

    /** 파일 경로 구분자 — Contents API 경로를 세그먼트로 쪼갤 때 쓴다. */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("/");

    /** owner/repo 추출 — https://host/owner/repo(.git)(/...) 와 git@host:owner/repo(.git) 를 받는다. */
    private static final Pattern HTTP_URL = Pattern.compile("^https?://([^/]+)/([^/]+)/([^/?#]+?)(?:\\.git)?(?:[/?#].*)?$");
    private static final Pattern SSH_URL  = Pattern.compile("^git@([^:]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final GitHubProperties properties;
    private final RestClient restClient;

    /**
     * 아카이브(zipball) 전용 클라이언트 — 읽기 타임아웃만 다르다(50초).
     *
     * <p>{@link JdkClientHttpRequestFactory#setReadTimeout} 은 헤더 수신까지가 아니라 <b>응답 본문까지 포함한 총 시간</b>이라
     * 기존 20초로는 큰 저장소의 압축본이 중간에 잘린다. nginx 기본 {@code proxy_read_timeout}(60초)보다 짧게 잡아
     * 서버가 먼저 명확한 메시지를 낸다. 기존 {@link #restClient} 는 그대로 둔다.
     */
    private final RestClient archiveClient;

    public GitHubScmClient(GitHubProperties properties)
    {
        this.properties = properties;
        this.restClient = buildClient(properties, Duration.ofSeconds(20));
        this.archiveClient = buildClient(properties, Duration.ofSeconds(50));
    }

    private static RestClient buildClient(GitHubProperties properties, Duration readTimeout)
    {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)   // 이름이 바뀐 저장소는 301, zipball 은 codeload 로 302
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(properties.token()))
        {
            builder.defaultHeader("Authorization", "Bearer " + properties.token());
        }
        return builder.build();
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
        return refUri(uri, ref, "branches", branch, "브랜치명이 올바르지 않습니다: ");
    }

    /**
     * zipball API URI — {@link #branchUri} 와 같은 규칙으로 ref(커밋 SHA 또는 브랜치명)를 세그먼트로 쪼개 넣는다
     * ({@code feature/x} 가 {@code %2F} 로 뭉개지지 않게).
     */
    static URI zipballUri(UriBuilder uri, RepoRef ref, String refValue)
    {
        return refUri(uri, ref, "zipball", refValue, "커밋/브랜치 이름이 올바르지 않습니다: ");
    }

    /** {@code /repos/{o}/{r}/{kind}/{ref 세그먼트…}}. 세그먼트는 URI 변수 값으로 넘겨 중괄호 등이 템플릿으로 해석되지 않게 한다. */
    private static URI refUri(UriBuilder uri, RepoRef ref, String kind, String refValue, String errorPrefix)
    {
        if (!StringUtils.hasText(refValue))
        {
            throw new ScmException(errorPrefix + refValue);
        }
        String[] segments = PATH_SEPARATOR.split(refValue, -1);
        List<Object> values = new ArrayList<>(List.of(ref.owner(), ref.repo()));
        uri.pathSegment("repos", "{owner}", "{repo}", kind);
        for (int i = 0; i < segments.length; i++)
        {
            /* '.'·'..' 는 Git 브랜치명에 올 수 없고, URI 정규화로 다른 API 경로를 가리킬 수 있어 거부한다. */
            if (segments[i].isEmpty() || ".".equals(segments[i]) || "..".equals(segments[i]))
            {
                throw new ScmException(errorPrefix + refValue);
            }
            uri.pathSegment("{b" + i + "}");
            values.add(segments[i]);
        }
        return uri.build(values.toArray());
    }

    /**
     * 커밋(또는 브랜치) 시점의 전체 파일 — GitHub 호출 1회({@code GET /repos/{o}/{r}/zipball/{ref}}, codeload 302 자동 추적).
     *
     * <p>압축본을 상한({@link #MAX_ARCHIVE_BYTES}) 안에서 메모리({@code byte[]})로 다 받고 <b>GitHub 연결을 닫은 뒤</b> 푼다.
     * 브라우저의 파일 쓰기 속도가 GitHub 다운로드를 붙잡지 않게 하기 위해서다. 서버 디스크(임시 파일)는 쓰지 않는다.
     * base URL 은 설정값으로 고정이라 사용자 입력이 호출 대상 호스트를 바꿀 수 없다.
     */
    @Override
    public void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException
    {
        RepoRef repo = require(repoUrl);
        byte[] zip;
        try
        {
            zip = archiveClient.get()
                    .uri(uri -> zipballUri(uri, repo, ref))
                    .exchange((request, response) ->
                    {
                        int status = response.getStatusCode().value();
                        if (status >= 400)
                        {
                            throw toScmException(repoUrl, status, null,
                                    "저장소 또는 커밋을 찾을 수 없음 (비공개 저장소면 토큰 필요)");
                        }
                        long length = response.getHeaders().getContentLength();
                        if (length > MAX_ARCHIVE_BYTES)
                        {
                            throw archiveTooLarge(repoUrl);
                        }
                        byte[] body = response.getBody().readNBytes((int) MAX_ARCHIVE_BYTES + 1);
                        if (body.length > MAX_ARCHIVE_BYTES)
                        {
                            throw archiveTooLarge(repoUrl);
                        }
                        return body;
                    });
        }
        catch (RestClientException e)
        {
            throw new ScmException("GitHub 호출 실패: " + repoUrl + " (" + e.getMessage() + ")", e);
        }
        if (zip == null || zip.length == 0)
        {
            throw new ScmException("GitHub 가 빈 압축본을 돌려줬습니다: " + repoUrl);
        }
        readZip(new ByteArrayInputStream(zip), visitor);
    }

    private static ScmException archiveTooLarge(String repoUrl)
    {
        return new ScmException("저장소 압축본이 상한(" + (MAX_ARCHIVE_BYTES / 1024 / 1024) + " MiB)을 넘어 내려받을 수 없습니다: " + repoUrl);
    }

    /**
     * zipball 을 순회하며 파일을 visitor 에 넘긴다 (순수 함수).
     *
     * <ul>
     *   <li>디렉터리 항목은 건너뛴다(보고하지 않음) — 빈 폴더는 만들지 않는다</li>
     *   <li>GitHub zipball 은 최상위 폴더({@code owner-repo-sha/}) 하나 아래에 전부 있다. 첫 항목의 첫 세그먼트를 그 폴더로
     *       고정하고 떼어 낸다. 다른 최상위로 시작하는 항목은 {@code onSkipped}</li>
     *   <li>{@link ScmPaths#safeRelativePath} 를 통과하지 못한 경로는 {@code onSkipped}</li>
     * </ul>
     * visitor 에 넘기는 스트림은 close 를 막은 래퍼다 — visitor 가 닫아도 다음 항목을 읽을 수 있다.
     */
    static void readZip(InputStream zip, ScmArchiveVisitor visitor) throws IOException
    {
        try (ZipInputStream in = new ZipInputStream(zip, StandardCharsets.UTF_8))
        {
            String root = null;
            boolean first = true;
            InputStream entryStream = new FilterInputStream(in)
            {
                @Override
                public void close()
                {
                    /* 닫으면 ZipInputStream 전체가 닫힌다 — 항목 경계는 getNextEntry 가 관리한다. */
                }
            };

            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null)
            {
                String name = entry.getName();
                if (first)
                {
                    first = false;
                    int slash = name.indexOf('/');
                    root = slash > 0 ? name.substring(0, slash) : null;
                }
                if (entry.isDirectory() || name.endsWith("/"))
                {
                    continue;
                }

                String relative = stripArchiveRoot(name, root);
                if (relative == null)
                {
                    visitor.onSkipped(name, "아카이브 최상위 폴더 밖의 항목");
                    continue;
                }
                if (relative.isEmpty())
                {
                    continue;
                }
                String safe = ScmPaths.safeRelativePath(relative);
                if (safe == null)
                {
                    visitor.onSkipped(relative, "허용되지 않는 경로");
                    continue;
                }
                visitor.onFile(safe, entryStream);
            }
        }
    }

    /**
     * 아카이브 최상위 폴더를 뗀다 — {@code root/a/b.txt} → {@code a/b.txt}, {@code root/} → {@code ""}(무시 대상),
     * 다른 최상위·최상위 없음 → null(건너뜀).
     */
    static String stripArchiveRoot(String entryName, String root)
    {
        if (entryName == null || root == null || root.isEmpty())
        {
            return null;
        }
        String prefix = root + "/";
        if (!entryName.startsWith(prefix))
        {
            return null;
        }
        return entryName.substring(prefix.length());
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
        return toScmException(repoUrl, e.getStatusCode().value(), e, "저장소를 찾을 수 없음 (비공개 저장소면 토큰 필요)");
    }

    /** 상태 코드 기준 변환 — {@code exchange} 처럼 예외 없이 상태를 직접 보는 호출용. 404 문구만 호출 측이 정한다. */
    private ScmException toScmException(String repoUrl, int status, Throwable cause, String notFoundReason)
    {
        boolean hasToken = StringUtils.hasText(properties.token());
        String reason = switch (status)
        {
            case 401 -> hasToken
                    ? "GitHub 인증 실패 (토큰이 만료됐거나 잘못됨)"
                    : "GitHub 인증 실패 — 토큰이 설정되지 않았습니다 (.env 의 GITHUB_TOKEN). "
                      + "공개 저장소 조회는 토큰 없이 되지만 푸시는 contents:write 권한이 있는 토큰이 필요합니다";
            case 403, 429 -> hasToken
                    ? "GitHub 접근 거부 또는 호출 한도 초과 (푸시에는 contents:write 권한이 필요하다)"
                    : "GitHub 접근 거부 또는 호출 한도 초과 (토큰 미설정 — 미인증 호출은 시간당 60회)";
            case 404 -> notFoundReason;
            case 409 -> "커밋 충돌 또는 빈 저장소";
            case 422 -> "GitHub 가 요청을 거절함 (경로·브랜치 확인 필요)";
            default -> "GitHub 응답 오류 " + status;
        };
        return new ScmException(reason + ": " + repoUrl, cause);
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
