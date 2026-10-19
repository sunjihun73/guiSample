package kr.co.jihun.guisample.scm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScmPathsTest
{
    @Test
    void 안전한_상대경로는_그대로_통과한다()
    {
        assertThat(ScmPaths.safeRelativePath("a.txt")).isEqualTo("a.txt");
        assertThat(ScmPaths.safeRelativePath("src/main/A.java")).isEqualTo("src/main/A.java");
        assertThat(ScmPaths.safeRelativePath("한글/파일.md")).isEqualTo("한글/파일.md");
        assertThat(ScmPaths.safeRelativePath("a b/c")).isEqualTo("a b/c");
        assertThat(ScmPaths.safeRelativePath(".github/workflows/ci.yml")).isEqualTo(".github/workflows/ci.yml");
        assertThat(ScmPaths.safeRelativePath("a..b/c")).isEqualTo("a..b/c");
    }

    @Test
    void 선택_폴더_밖을_가리킬_수_있는_경로는_거부한다()
    {
        assertThat(ScmPaths.safeRelativePath(null)).isNull();
        assertThat(ScmPaths.safeRelativePath("")).isNull();
        assertThat(ScmPaths.safeRelativePath("   ")).isNull();
        assertThat(ScmPaths.safeRelativePath("/etc/passwd")).isNull();
        assertThat(ScmPaths.safeRelativePath("../x")).isNull();
        assertThat(ScmPaths.safeRelativePath("a/../x")).isNull();
        assertThat(ScmPaths.safeRelativePath("a/..")).isNull();
        assertThat(ScmPaths.safeRelativePath("a/./b")).isNull();
        assertThat(ScmPaths.safeRelativePath("a//b")).isNull();
        assertThat(ScmPaths.safeRelativePath("a\\b")).isNull();
        assertThat(ScmPaths.safeRelativePath("C:/x")).isNull();
        assertThat(ScmPaths.safeRelativePath("c:x")).isNull();
        assertThat(ScmPaths.safeRelativePath("a\u0000b")).isNull();
        assertThat(ScmPaths.safeRelativePath("dir/")).isNull();
    }
}
