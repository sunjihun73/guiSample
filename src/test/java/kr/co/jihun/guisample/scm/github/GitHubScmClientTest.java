package kr.co.jihun.guisample.scm.github;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.URI;

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
}
