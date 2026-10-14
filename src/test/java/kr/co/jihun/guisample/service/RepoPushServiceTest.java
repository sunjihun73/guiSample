package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import kr.co.jihun.guisample.dto.RepoPushResult;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmPushResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoPushServiceTest
{
    private static final String USER = "tester";
    private static final String REPO = "https://scm.example.com/team/app";
    /* 날짜 경계를 커밋 메시지가 어느 시각에 찍히는지로 확인하기 위해 KST 로 고정한다. */
    private static final Instant NOW = Instant.parse("2026-10-13T15:30:00Z");   // KST 2026-10-14 00:30

    private final ProjectMasterService projectMasterService = mock(ProjectMasterService.class);
    private final FakeScmClient scm = new FakeScmClient();
    private final RepoPushService service = new RepoPushService(
            projectMasterService, new ScmClientResolver(List.of(scm)),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    void 설명_HTML_을_src_docs_project_id_txt_로_푸시한다()
    {
        given(project("p1", REPO, "<p>설명 {본문}</p>"));

        RepoPushResult result = service.pushProjectDescription("p1", USER);

        assertThat(scm.path).isEqualTo("src/docs/p1.txt");
        assertThat(scm.content).isEqualTo("<p>설명 {본문}</p>");
        assertThat(scm.message).isEqualTo("프로젝트 설명 2026-10-14");    // 실행 시각의 로컬 날짜
        assertThat(result.path()).isEqualTo("src/docs/p1.txt");
        assertThat(result.commitMessage()).isEqualTo("프로젝트 설명 2026-10-14");
        assertThat(result.commitId()).isEqualTo("sha1");
        assertThat(result.created()).isTrue();
    }

    @Test
    void 남의_프로젝트거나_없으면_푸시하지_않는다()
    {
        when(projectMasterService.selectProjectMaster("p1", USER)).thenReturn(null);

        assertThatThrownBy(() -> service.pushProjectDescription("p1", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("찾을 수 없습니다");
        assertThat(scm.path).isNull();
    }

    @Test
    void 저장소_URL_이_없으면_푸시하지_않는다()
    {
        given(project("p1", "  ", "<p>설명</p>"));

        assertThatThrownBy(() -> service.pushProjectDescription("p1", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장소 URL");
        assertThat(scm.path).isNull();
    }

    /** 빈 파일을 올려 저장소의 기존 문서를 지워 버리지 않도록 막는다. */
    @Test
    void 설명이_비어_있으면_푸시하지_않는다()
    {
        given(project("p1", REPO, "   "));

        assertThatThrownBy(() -> service.pushProjectDescription("p1", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있어");
        assertThat(scm.path).isNull();
    }

    @Test
    void 지원하지_않는_저장소면_실패한다()
    {
        given(project("p1", "svn://svn.example.com/repo/trunk", "<p>설명</p>"));

        assertThatThrownBy(() -> service.pushProjectDescription("p1", USER))
                .hasMessageContaining("지원하지 않는 저장소");
    }

    private void given(ProjectMasterDTO project)
    {
        when(projectMasterService.selectProjectMaster(project.getProjectId(), USER)).thenReturn(project);
    }

    private static ProjectMasterDTO project(String id, String repoUrl, String description)
    {
        ProjectMasterDTO dto = new ProjectMasterDTO();
        dto.setProjectId(id);
        dto.setProjectName("프로젝트 " + id);
        dto.setRepoUrl(repoUrl);
        dto.setProjectDescription(description);
        return dto;
    }

    /** putFile 인자를 받아 두는 가짜 저장소 — scm.example.com 만 맡는다. */
    private static final class FakeScmClient implements ScmClient
    {
        private String path;
        private String content;
        private String message;

        @Override
        public boolean supports(String repoUrl)
        {
            return repoUrl != null && repoUrl.startsWith("https://scm.example.com/");
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

        @Override
        public ScmPushResult putFile(String repoUrl, String path, String content, String commitMessage)
        {
            this.path = path;
            this.content = content;
            this.message = commitMessage;
            return new ScmPushResult(path, "sha1", true);
        }
    }
}
