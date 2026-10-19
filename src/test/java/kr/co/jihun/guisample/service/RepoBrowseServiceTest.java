package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.RepoFileRow;
import kr.co.jihun.guisample.dto.RepoTreeSummary;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmEntryType;
import kr.co.jihun.guisample.scm.ScmException;
import kr.co.jihun.guisample.scm.ScmTree;
import kr.co.jihun.guisample.scm.ScmTreeEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoBrowseServiceTest
{
    private static final String REPO = "https://scm.example.com/team/app.git";

    private final FakeScmClient scm = new FakeScmClient();
    private final RepoBrowseService service = new RepoBrowseService(new ScmClientResolver(List.of(scm)));

    @Test
    void 경로에서_이름_상위경로_깊이를_계산한다()
    {
        List<RepoFileRow> rows = RepoBrowseService.toRows(List.of(
                dir("a"), dir("a/b"), file("a/b/c.txt", 12L)), 100).rows();

        assertThat(rows).extracting(RepoFileRow::path, RepoFileRow::name, RepoFileRow::parentPath, RepoFileRow::depth)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a", "a", "", 0),
                        org.assertj.core.groups.Tuple.tuple("a/b", "b", "a", 1),
                        org.assertj.core.groups.Tuple.tuple("a/b/c.txt", "c.txt", "a/b", 2));
        assertThat(rows.get(2).size()).isEqualTo(12L);
        assertThat(rows.get(2).type()).isEqualTo("FILE");
        assertThat(rows.get(0).size()).isNull();
    }

    @Test
    void 같은_폴더에서_폴더_먼저_대소문자_무시_이름순으로_정렬한다()
    {
        List<RepoFileRow> rows = RepoBrowseService.toRows(List.of(
                file("build.gradle", 1L), file("README.md", 1L), dir("src"), dir("Docs"),
                new ScmTreeEntry("lib", ScmEntryType.SUBMODULE, null, null),
                file("src/A.java", 1L), file("apple.txt", 1L)), 100).rows();

        assertThat(rows).extracting(RepoFileRow::path)
                .containsExactly("Docs", "lib", "src", "apple.txt", "build.gradle", "README.md", "src/A.java");
    }

    @Test
    void 파일이_아닌_항목의_크기는_null_로_내린다()
    {
        List<RepoFileRow> rows = RepoBrowseService.toRows(List.of(
                new ScmTreeEntry("link", ScmEntryType.SYMLINK, 7L, "u")), 100).rows();

        assertThat(rows.getFirst().size()).isNull();
        assertThat(rows.getFirst().type()).isEqualTo("SYMLINK");
    }

    @Test
    void 상한을_넘으면_잘라내고_truncated_로_알린다()
    {
        List<ScmTreeEntry> entries = new ArrayList<>();
        for (int i = 0; i < RepoBrowseService.MAX_ROWS + 5; i++)
        {
            entries.add(file("f" + i, 1L));
        }

        RepoBrowseService.Rows over = RepoBrowseService.toRows(entries, RepoBrowseService.MAX_ROWS);
        RepoBrowseService.Rows exact = RepoBrowseService.toRows(entries.subList(0, 3), 3);

        assertThat(over.rows()).hasSize(RepoBrowseService.MAX_ROWS);
        assertThat(over.truncated()).isTrue();
        assertThat(exact.rows()).hasSize(3);
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void URL_이_비었으면_저장소를_부르지_않고_거절한다()
    {
        assertThatThrownBy(() -> service.browse("   ", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장소 URL 을 입력하세요");
        assertThatThrownBy(() -> service.browse(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scm.calls).isZero();
    }

    @Test
    void 지원하지_않는_URL_은_트리_조회_전에_거절한다()
    {
        assertThatThrownBy(() -> service.browse("https://gitlab.com/a/b", null))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("지원하지 않는 저장소 URL");
        assertThat(scm.calls).isZero();
    }

    @Test
    void 빈_브랜치는_null_로_넘기고_결과를_summary_로_옮긴다()
    {
        scm.tree = new ScmTree("main", "main", "abc123", "first\n\nbody",
                Instant.parse("2026-10-14T06:52:22Z"), false,
                List.of(dir("src"), file("src/a.txt", 3L), file("build.gradle", 10L)));

        RepoBrowseService.Result result = service.browse("  " + REPO + "  ", "  ");

        assertThat(scm.lastRepoUrl).isEqualTo(REPO);
        assertThat(scm.lastBranch).isNull();
        RepoTreeSummary summary = result.summary();
        assertThat(summary.repoUrl()).isEqualTo(REPO);
        assertThat(summary.owner()).isEqualTo("team");
        assertThat(summary.repo()).isEqualTo("app");
        assertThat(summary.branch()).isEqualTo("main");
        assertThat(summary.defaultBranch()).isEqualTo("main");
        assertThat(summary.commitId()).isEqualTo("abc123");
        assertThat(summary.commitMessage()).isEqualTo("first\n\nbody");
        assertThat(summary.commitDate()).isEqualTo("2026-10-14T06:52:22Z");
        assertThat(summary.truncated()).isFalse();
        assertThat(summary.entryCount()).isEqualTo(3);
        assertThat(result.rows()).extracting(RepoFileRow::path).containsExactly("src", "build.gradle", "src/a.txt");
    }

    @Test
    void 브랜치는_앞뒤_공백을_떼고_넘기며_저장소_쪽_잘림을_그대로_알린다()
    {
        scm.tree = new ScmTree("feature/x", "main", "abc", "m", null, true, List.of());

        RepoBrowseService.Result result = service.browse(REPO, " feature/x ");

        assertThat(scm.lastBranch).isEqualTo("feature/x");
        assertThat(result.summary().truncated()).isTrue();
        assertThat(result.summary().commitDate()).isNull();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    void URL_에서_표시용_owner_repo_를_꺼낸다()
    {
        assertThat(RepoBrowseService.ownerAndRepo("https://github.com/sunjihun73/guiSample.git"))
                .containsExactly("sunjihun73", "guiSample");
        assertThat(RepoBrowseService.ownerAndRepo("https://github.com/acme/app/tree/main/src"))
                .containsExactly("acme", "app");
        assertThat(RepoBrowseService.ownerAndRepo("git@github.com:acme/app.git"))
                .containsExactly("acme", "app");
        assertThat(RepoBrowseService.ownerAndRepo("https://github.com/acme/app/"))
                .containsExactly("acme", "app");
        assertThat(RepoBrowseService.ownerAndRepo("https://github.com/acme"))
                .containsExactly(null, null);
    }

    private static ScmTreeEntry dir(String path)
    {
        return new ScmTreeEntry(path, ScmEntryType.DIR, null, "https://x/tree/" + path);
    }

    private static ScmTreeEntry file(String path, Long size)
    {
        return new ScmTreeEntry(path, ScmEntryType.FILE, size, "https://x/blob/" + path);
    }

    /** findTree 만 쓰는 가짜 구현 — 커밋 조회 메서드는 이 테스트에서 불리지 않는다. */
    private static class FakeScmClient implements ScmClient
    {
        ScmTree tree = new ScmTree("main", "main", "c", "", null, false, List.of());
        String lastRepoUrl;
        String lastBranch;
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
        public ScmTree findTree(String repoUrl, String branch)
        {
            calls++;
            lastRepoUrl = repoUrl;
            lastBranch = branch;
            return tree;
        }
    }
}
