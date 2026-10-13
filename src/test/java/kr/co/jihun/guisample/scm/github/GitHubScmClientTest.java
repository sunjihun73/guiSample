package kr.co.jihun.guisample.scm.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
