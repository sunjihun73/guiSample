package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.scm.ScmArchiveVisitor;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoArchiveServiceTest
{
    private static final String REPO = "https://scm.example.com/team/app.git";

    private final FakeScmClient scm = new FakeScmClient();
    private final RepoArchiveService service = new RepoArchiveService(new ScmClientResolver(List.of(scm)));
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final RepoArchiveFrameWriter writer = new RepoArchiveFrameWriter(out);

    @Test
    void URL_이나_ref_가_비었으면_저장소를_부르지_않고_거절한다()
    {
        assertThatThrownBy(() -> service.streamArchive("  ", "abc", writer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장소 URL 을 입력하세요");
        assertThatThrownBy(() -> service.streamArchive(REPO, " ", writer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("커밋 ID 가 없습니다");
        assertThatThrownBy(() -> service.streamArchive(REPO, null, writer))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scm.calls).isZero();
        assertThat(out.size()).isZero();
    }

    @Test
    void 지원하지_않는_URL_은_내려받기_전에_거절한다()
    {
        assertThatThrownBy(() -> service.streamArchive("https://gitlab.com/a/b", "abc", writer))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("지원하지 않는 저장소 URL");
        assertThat(scm.calls).isZero();
    }

    @Test
    void 파일과_건너뜀을_프레임으로_쓰고_개수와_합계를_돌려준다() throws IOException
    {
        scm.files = List.of(new String[] { "a.txt", "hello" }, new String[] { "src/B.java", "class B {}" },
                new String[] { "empty", "" });
        scm.skipped = List.of("../evil.txt");

        RepoArchiveService.Result result = service.streamArchive("  " + REPO + " ", " abc123 ", writer);

        assertThat(scm.lastRepoUrl).isEqualTo(REPO);
        assertThat(scm.lastRef).isEqualTo("abc123");
        assertThat(result).isEqualTo(new RepoArchiveService.Result(3, 1, 15));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertThat(in.readByte()).isEqualTo((byte) 'S');
        assertThat(readString(in)).isEqualTo("../evil.txt");
        readString(in);
        assertThat(in.readByte()).isEqualTo((byte) 'F');
        assertThat(readString(in)).isEqualTo("a.txt");
        assertThat(new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void 파일_수가_상한을_넘으면_중단한다()
    {
        String[][] many = new String[RepoArchiveService.MAX_FILES + 1][];
        for (int i = 0; i < many.length; i++)
        {
            many[i] = new String[] { "f" + i, "" };
        }
        scm.files = List.of(many);

        assertThatThrownBy(() -> service.streamArchive(REPO, "abc", writer))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("파일 수가 상한");
    }

    @Test
    void readLimited_는_상한까지_읽고_넘으면_예외다() throws IOException
    {
        assertThat(RepoArchiveService.readLimited(new ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
        assertThat(RepoArchiveService.readLimited(new ByteArrayInputStream(new byte[0]), 10)).isEmpty();
        assertThatThrownBy(() -> RepoArchiveService.readLimited(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("상한");
    }

    @Test
    void 저장소_오류는_그대로_전파된다()
    {
        scm.failure = new ScmException("저장소 또는 커밋을 찾을 수 없음");

        assertThatThrownBy(() -> service.streamArchive(REPO, "abc", writer))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("찾을 수 없음");
    }

    private static String readString(DataInputStream in) throws IOException
    {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    /** readArchive 만 쓰는 가짜 구현 — 건너뜀을 먼저, 파일을 다음에 넘긴다. */
    private static class FakeScmClient implements ScmClient
    {
        List<String[]> files = List.of();
        List<String> skipped = List.of();
        ScmException failure;
        String lastRepoUrl;
        String lastRef;
        int calls;

        @Override
        public boolean supports(String repoUrl)
        {
            return repoUrl.startsWith("https://scm.example.com/");
        }

        @Override
        public List<ScmCommit> findCommitsSince(String repoUrl, Instant since)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> findChangedFiles(String repoUrl, String commitId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException
        {
            calls++;
            lastRepoUrl = repoUrl;
            lastRef = ref;
            if (failure != null)
            {
                throw failure;
            }
            for (String path : skipped)
            {
                visitor.onSkipped(path, "허용되지 않는 경로");
            }
            for (String[] file : files)
            {
                InputStream content = new ByteArrayInputStream(file[1].getBytes(StandardCharsets.UTF_8));
                visitor.onFile(file[0], content);
            }
        }
    }
}
