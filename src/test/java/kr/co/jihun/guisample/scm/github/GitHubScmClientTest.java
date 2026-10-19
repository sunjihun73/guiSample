package kr.co.jihun.guisample.scm.github;

import com.sun.net.httpserver.HttpServer;
import kr.co.jihun.guisample.scm.ScmArchiveVisitor;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmEntryType;
import kr.co.jihun.guisample.scm.ScmException;
import kr.co.jihun.guisample.scm.ScmTreeEntry;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubScmClientTest
{
    private final GitHubScmClient client = new GitHubScmClient(
            new GitHubProperties("https://api.github.com", "github.com", "", 10));

    @Test
    void 여러_형태의_저장소_URL_에서_owner_repo_를_꺼낸다()
    {
        assertThat(GitHubScmClient.parse("https://github.com/acme/app"))
                .isEqualTo(new GitHubScmClient.RepoRef("github.com", "acme", "app"));
        assertThat(GitHubScmClient.parse(" https://github.com/acme/app.git "))
                .isEqualTo(new GitHubScmClient.RepoRef("github.com", "acme", "app"));
        assertThat(GitHubScmClient.parse("https://github.com/acme/app/tree/main/src"))
                .isEqualTo(new GitHubScmClient.RepoRef("github.com", "acme", "app"));
        assertThat(GitHubScmClient.parse("git@github.com:acme/app.git"))
                .isEqualTo(new GitHubScmClient.RepoRef("github.com", "acme", "app"));
        assertThat(GitHubScmClient.parse("https://github.com/acme")).isNull();
        assertThat(GitHubScmClient.parse("")).isNull();
    }

    @Test
    void 설정된_호스트의_URL_만_맡는다()
    {
        assertThat(client.supports("https://github.com/acme/app")).isTrue();
        assertThat(client.supports("https://GitHub.com/acme/app")).isTrue();
        assertThat(client.supports("https://gitlab.com/acme/app")).isFalse();
        assertThat(client.supports("svn://svn.example.com/repo/trunk")).isFalse();
    }

    /**
     * REPO PUSH 대상 경로(src/docs/…)의 슬래시가 %2F 로 인코딩되면 GitHub 가 404 를 준다.
     * RestClient 와 같은 인코딩 모드(TEMPLATE_AND_VALUES)로 URI 를 만들어 경로가 그대로 남는지 본다.
     */
    @Test
    void Contents_API_경로의_슬래시는_인코딩되지_않는다()
    {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://api.github.com");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        GitHubScmClient.RepoRef ref = new GitHubScmClient.RepoRef("github.com", "acme", "app");

        URI uri = GitHubScmClient.contentsUri(factory.builder(), ref, "src/docs/8f3c-1.txt");

        assertThat(uri.toString())
                .isEqualTo("https://api.github.com/repos/acme/app/contents/src/docs/8f3c-1.txt");
    }

    @Test
    void GitHub_가_아닌_URL_로_푸시하면_거절한다()
    {
        assertThatThrownBy(() -> client.putFile("svn://svn.example.com/repo/trunk", "src/docs/a.txt", "x", "m"))
                .hasMessageContaining("GitHub 저장소 URL 형식이 아닙니다");
    }

    private static final GitHubScmClient.RepoRef REF = new GitHubScmClient.RepoRef("github.com", "acme", "app");
    private static final String SHA = "71de038f8284d920449da7f14e4e6db2d2aac327";

    @Test
    void 트리_항목_종류를_저장소_중립_종류로_바꾼다()
    {
        assertThat(GitHubScmClient.entryType("tree", "040000")).isEqualTo(ScmEntryType.DIR);
        assertThat(GitHubScmClient.entryType("blob", "100644")).isEqualTo(ScmEntryType.FILE);
        assertThat(GitHubScmClient.entryType("blob", "100755")).isEqualTo(ScmEntryType.FILE);
        assertThat(GitHubScmClient.entryType("blob", "120000")).isEqualTo(ScmEntryType.SYMLINK);
        assertThat(GitHubScmClient.entryType("commit", "160000")).isEqualTo(ScmEntryType.SUBMODULE);
        assertThat(GitHubScmClient.entryType("tag", null)).isNull();
        assertThat(GitHubScmClient.entryType(null, null)).isNull();
    }

    @Test
    void 트리_항목을_변환할_때_크기는_파일만_남긴다()
    {
        ScmTreeEntry file = GitHubScmClient.toEntry(
                new GitHubScmClient.TreeItem("build.gradle", "100644", "blob", "s1", 2310L), "github.com", REF, SHA);
        ScmTreeEntry link = GitHubScmClient.toEntry(
                new GitHubScmClient.TreeItem("ln", "120000", "blob", "s2", 9L), "github.com", REF, SHA);
        ScmTreeEntry dir = GitHubScmClient.toEntry(
                new GitHubScmClient.TreeItem("src", "040000", "tree", "s3", null), "github.com", REF, SHA);
        ScmTreeEntry sub = GitHubScmClient.toEntry(
                new GitHubScmClient.TreeItem("vendor/lib", "160000", "commit", "s4", null), "github.com", REF, SHA);

        assertThat(file).isEqualTo(new ScmTreeEntry("build.gradle", ScmEntryType.FILE, 2310L,
                "https://github.com/acme/app/blob/" + SHA + "/build.gradle"));
        assertThat(link.type()).isEqualTo(ScmEntryType.SYMLINK);
        assertThat(link.size()).isNull();
        assertThat(dir.size()).isNull();
        assertThat(sub.webUrl()).isNull();
        assertThat(GitHubScmClient.toEntry(
                new GitHubScmClient.TreeItem("x", "000000", "tag", "s5", null), "github.com", REF, SHA)).isNull();
    }

    @Test
    void 웹_주소는_폴더_tree_파일_blob_으로_만들고_경로_슬래시를_유지한다()
    {
        assertThat(GitHubScmClient.webUrl("github.com", REF, SHA, "src/main/App.java", ScmEntryType.FILE))
                .isEqualTo("https://github.com/acme/app/blob/" + SHA + "/src/main/App.java");
        assertThat(GitHubScmClient.webUrl("github.com", REF, SHA, "src/main", ScmEntryType.DIR))
                .isEqualTo("https://github.com/acme/app/tree/" + SHA + "/src/main");
        assertThat(GitHubScmClient.webUrl("github.com", REF, SHA, "lib", ScmEntryType.SUBMODULE)).isNull();
    }

    @Test
    void 웹_주소의_특수문자는_세그먼트_단위로_인코딩한다()
    {
        assertThat(GitHubScmClient.webUrl("github.com", REF, SHA, "docs/a b#{x}.md", ScmEntryType.FILE))
                .isEqualTo("https://github.com/acme/app/blob/" + SHA + "/docs/a%20b%23%7Bx%7D.md");
    }

    /** 슬래시가 든 브랜치명이 %2F 로 뭉개지지 않고 세그먼트로 들어가는지 (RestClient 와 같은 인코딩 모드). */
    @Test
    void 슬래시가_든_브랜치명은_세그먼트로_쪼개_넣는다()
    {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://api.github.com");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);

        assertThat(GitHubScmClient.branchUri(factory.builder(), REF, "feature/x").toString())
                .isEqualTo("https://api.github.com/repos/acme/app/branches/feature/x");
        assertThat(GitHubScmClient.branchUri(factory.builder(), REF, "dev").toString())
                .isEqualTo("https://api.github.com/repos/acme/app/branches/dev");
        assertThat(GitHubScmClient.branchUri(factory.builder(), REF, "fix/{id}").toString())
                .isEqualTo("https://api.github.com/repos/acme/app/branches/fix/%7Bid%7D");
        assertThatThrownBy(() -> GitHubScmClient.branchUri(factory.builder(), REF, "a//b"))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> GitHubScmClient.branchUri(factory.builder(), REF, "../../x"))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> GitHubScmClient.branchUri(factory.builder(), REF, "a/./b"))
                .isInstanceOf(ScmException.class);
    }

    @Test
    void GitHub_가_아닌_URL_로_트리를_조회하면_거절한다()
    {
        assertThatThrownBy(() -> client.findTree("svn://svn.example.com/repo/trunk", null))
                .hasMessageContaining("GitHub 저장소 URL 형식이 아닙니다");
    }

    @Test
    void 트리_조회를_구현하지_않은_저장소는_기본_구현이_거절한다()
    {
        ScmClient readOnly = new ScmClient()
        {
            @Override
            public boolean supports(String repoUrl)
            {
                return true;
            }

            @Override
            public List<ScmCommit> findCommitsSince(String repoUrl, Instant since)
            {
                return List.of();
            }

            @Override
            public List<String> findChangedFiles(String repoUrl, String commitId)
            {
                return List.of();
            }
        };

        assertThatThrownBy(() -> readOnly.findTree("svn://x/repo", null))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("파일 목록 조회를 지원하지 않습니다");
    }

    /* ---------- 저장소끌어오기 저장 (zipball) ---------- */

    @Test
    void zipball_URI_는_ref_를_세그먼트로_쪼개_넣는다()
    {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://api.github.com");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);

        assertThat(GitHubScmClient.zipballUri(factory.builder(), REF, SHA).toString())
                .isEqualTo("https://api.github.com/repos/acme/app/zipball/" + SHA);
        assertThat(GitHubScmClient.zipballUri(factory.builder(), REF, "feature/x").toString())
                .isEqualTo("https://api.github.com/repos/acme/app/zipball/feature/x");
        assertThatThrownBy(() -> GitHubScmClient.zipballUri(factory.builder(), REF, "../x"))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("커밋/브랜치 이름이 올바르지 않습니다");
        assertThatThrownBy(() -> GitHubScmClient.zipballUri(factory.builder(), REF, "a/./b"))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> GitHubScmClient.zipballUri(factory.builder(), REF, "a//b"))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> GitHubScmClient.zipballUri(factory.builder(), REF, ""))
                .isInstanceOf(ScmException.class);
    }

    @Test
    void 아카이브_최상위_폴더를_뗀다()
    {
        assertThat(GitHubScmClient.stripArchiveRoot("acme-app-8a63fb2/src/A.java", "acme-app-8a63fb2"))
                .isEqualTo("src/A.java");
        assertThat(GitHubScmClient.stripArchiveRoot("acme-app-8a63fb2/", "acme-app-8a63fb2")).isEmpty();
        assertThat(GitHubScmClient.stripArchiveRoot("other/x.txt", "acme-app-8a63fb2")).isNull();
        assertThat(GitHubScmClient.stripArchiveRoot("acme-app-8a63fb2x/y", "acme-app-8a63fb2")).isNull();
        assertThat(GitHubScmClient.stripArchiveRoot("x.txt", null)).isNull();
    }

    @Test
    void zip_을_순회하며_디렉터리는_무시하고_위험한_경로는_건너뛴다() throws IOException
    {
        byte[] zip = zip("root/", null, "root/src/", null,
                "root/a.txt", "A", "root/src/B.java", "class B {}", "root/../evil.txt", "x",
                "other/x.txt", "y", "root/empty.txt", "");
        RecordingVisitor visitor = new RecordingVisitor();

        GitHubScmClient.readZip(new ByteArrayInputStream(zip), visitor);

        assertThat(visitor.files).containsExactly(
                Map.entry("a.txt", "A"), Map.entry("src/B.java", "class B {}"), Map.entry("empty.txt", ""));
        assertThat(visitor.skipped).containsExactly(
                Map.entry("../evil.txt", "허용되지 않는 경로"),
                Map.entry("other/x.txt", "아카이브 최상위 폴더 밖의 항목"));
    }

    @Test
    void visitor_가_스트림을_닫아도_다음_항목을_읽는다() throws IOException
    {
        byte[] zip = zip("r/a.txt", "1", "r/b.txt", "22", "r/c.txt", "333");
        List<String> seen = new ArrayList<>();

        GitHubScmClient.readZip(new ByteArrayInputStream(zip), (path, content) ->
        {
            seen.add(path + "=" + new String(content.readAllBytes(), StandardCharsets.UTF_8));
            content.close();
        });

        assertThat(seen).containsExactly("a.txt=1", "b.txt=22", "c.txt=333");
    }

    @Test
    void 최상위_폴더가_없는_zip_의_파일은_모두_건너뛴다() throws IOException
    {
        byte[] zip = zip("a.txt", "1", "b/c.txt", "2");
        RecordingVisitor visitor = new RecordingVisitor();

        GitHubScmClient.readZip(new ByteArrayInputStream(zip), visitor);

        assertThat(visitor.files).isEmpty();
        assertThat(visitor.skipped).extracting(Map.Entry::getKey).containsExactly("a.txt", "b/c.txt");
    }

    /**
     * 실제 HTTP 경로 — 루프백 가짜 서버(외부 네트워크 없음)로 302 리다이렉트 추적, 404 문구,
     * exchange 안에서 던진 ScmException 이 RestClient 에 감싸이지 않고 나오는지 확인한다.
     */
    @Test
    void zipball_을_리다이렉트를_따라_받아_풀고_404_는_저장소_또는_커밋_문구로_바꾼다() throws IOException
    {
        byte[] zip = zip("acme-app-abc/", null, "acme-app-abc/README.md", "hi");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/acme/app/zipball/abc", exchange ->
        {
            exchange.getResponseHeaders().add("Location", "/codeload/acme/app/abc.zip");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/codeload/acme/app/abc.zip", exchange ->
        {
            exchange.sendResponseHeaders(200, zip.length);
            try (OutputStream body = exchange.getResponseBody())
            {
                body.write(zip);
            }
        });
        server.createContext("/repos/acme/app/zipball/missing", exchange ->
        {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try
        {
            GitHubScmClient local = new GitHubScmClient(new GitHubProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "github.com", "", 10));
            RecordingVisitor visitor = new RecordingVisitor();

            local.readArchive("https://github.com/acme/app.git", "abc", visitor);

            assertThat(visitor.files).containsExactly(Map.entry("README.md", "hi"));
            assertThatThrownBy(() -> local.readArchive("https://github.com/acme/app", "missing", new RecordingVisitor()))
                    .isInstanceOf(ScmException.class)
                    .hasMessageContaining("저장소 또는 커밋을 찾을 수 없음");
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void GitHub_가_아닌_URL_로_내려받으면_거절하고_기본_구현도_거절한다()
    {
        assertThatThrownBy(() -> client.readArchive("svn://svn.example.com/repo/trunk", "abc", new RecordingVisitor()))
                .hasMessageContaining("GitHub 저장소 URL 형식이 아닙니다");

        ScmClient readOnly = new ScmClient()
        {
            @Override
            public boolean supports(String repoUrl)
            {
                return true;
            }

            @Override
            public List<ScmCommit> findCommitsSince(String repoUrl, Instant since)
            {
                return List.of();
            }

            @Override
            public List<String> findChangedFiles(String repoUrl, String commitId)
            {
                return List.of();
            }
        };
        assertThatThrownBy(() -> readOnly.readArchive("svn://x/repo", "abc", new RecordingVisitor()))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("파일 내려받기를 지원하지 않습니다");
    }

    /** 이름·내용 쌍으로 zip 을 만든다. 내용이 null 이면 디렉터리 항목. */
    private static byte[] zip(String... nameAndContent) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8))
        {
            for (int i = 0; i < nameAndContent.length; i += 2)
            {
                out.putNextEntry(new ZipEntry(nameAndContent[i]));
                if (nameAndContent[i + 1] != null)
                {
                    out.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static class RecordingVisitor implements ScmArchiveVisitor
    {
        final List<Map.Entry<String, String>> files = new ArrayList<>();
        final List<Map.Entry<String, String>> skipped = new ArrayList<>();

        @Override
        public void onFile(String path, InputStream content) throws IOException
        {
            files.add(Map.entry(path, new String(content.readAllBytes(), StandardCharsets.UTF_8)));
        }

        @Override
        public void onSkipped(String path, String reason)
        {
            skipped.add(Map.entry(path, reason));
        }
    }
}
