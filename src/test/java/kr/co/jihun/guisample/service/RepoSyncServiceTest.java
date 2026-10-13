package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import kr.co.jihun.guisample.dto.RepoSyncResult;
import kr.co.jihun.guisample.dto.RepoSyncResult.Status;
import kr.co.jihun.guisample.mapper.ProjectMasterMapper;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmCommit;
import kr.co.jihun.guisample.scm.ScmException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoSyncServiceTest
{
    private static final String USER = "tester";
    private static final String REPO = "https://scm.example.com/team/app";
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    private final ProjectMasterMapper mapper = mock(ProjectMasterMapper.class);
    private final FakeScmClient scm = new FakeScmClient();
    private final RepoSyncService service = new RepoSyncService(
            mapper, new ScmClientResolver(List.of(scm)), 30, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 커밋_메시지의_중괄호_값을_모두_꺼낸다()
    {
        assertThat(RepoSyncService.extractReqIds("fix {REQ-1} and { REQ-2 } again {REQ-1} {} {  }"))
                .containsExactly("REQ-1", "REQ-2");
        assertThat(RepoSyncService.extractReqIds("no marker")).isEmpty();
        assertThat(RepoSyncService.extractReqIds(null)).isEmpty();
    }

    @Test
    void 일치_커밋들의_파일을_합쳐_정렬해_file_names_를_교체한다()
    {
        givenProjects(project("p1", "REQ-1", REPO, "old.txt"));
        scm.commits = List.of(
                new ScmCommit("c1", "{REQ-1} first"),
                new ScmCommit("c2", "other {REQ-9}"),
                new ScmCommit("c3", "second\n\n{REQ-1}"));
        scm.files = Map.of("c1", List.of("src/B.java", "src/A.java"),
                           "c2", List.of("x.txt"),
                           "c3", List.of("src/A.java", "web/c.jsp"));
        when(mapper.updateProjectFileNames(anyString(), anyString(), anyString())).thenReturn(1);

        RepoSyncResult result = service.syncFileNames(USER);

        verify(mapper).updateProjectFileNames("p1", "src/A.java\nsrc/B.java\nweb/c.jsp", USER);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.items().getFirst().commitCount()).isEqualTo(2);
        assertThat(result.items().getFirst().fileCount()).isEqualTo(3);
        assertThat(scm.since).isEqualTo(Instant.parse("2026-09-13T00:00:00Z"));   // 30일 전
        assertThat(scm.fileCalls).containsExactlyInAnyOrder("c1", "c3");          // 일치 커밋만 파일 조회
    }

    @Test
    void 일치_커밋이_없으면_기존_값을_건드리지_않는다()
    {
        givenProjects(project("p1", "REQ-1", REPO, "keep.txt"));
        scm.commits = List.of(new ScmCommit("c1", "{REQ-2}"));

        RepoSyncResult result = service.syncFileNames(USER);

        assertThat(result.noMatch()).isEqualTo(1);
        verify(mapper, never()).updateProjectFileNames(any(), any(), any());
    }

    @Test
    void 파일_목록이_같으면_갱신하지_않는다()
    {
        givenProjects(project("p1", "REQ-1", REPO, "a.txt\nb.txt"));
        scm.commits = List.of(new ScmCommit("c1", "{REQ-1}"));
        scm.files = Map.of("c1", List.of("b.txt", "a.txt"));

        assertThat(service.syncFileNames(USER).unchanged()).isEqualTo(1);
        verify(mapper, never()).updateProjectFileNames(any(), any(), any());
    }

    @Test
    void 같은_저장소는_커밋_목록을_한_번만_읽고_repo_url_이나_req_id_가_없는_프로젝트는_제외한다()
    {
        givenProjects(
                project("p1", "REQ-1", REPO, null),
                project("p2", "REQ-2", " " + REPO + " ", null),
                project("p3", null, REPO, null),
                project("p4", "REQ-4", "", null));
        scm.commits = List.of(new ScmCommit("c1", "{REQ-1}{REQ-2}"));
        scm.files = Map.of("c1", List.of("a.txt"));
        when(mapper.updateProjectFileNames(anyString(), anyString(), anyString())).thenReturn(1);

        RepoSyncResult result = service.syncFileNames(USER);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(scm.listCalls).isEqualTo(1);
        assertThat(scm.fileCalls).containsExactly("c1");    // 두 프로젝트가 같은 커밋 → 파일도 한 번만
    }

    @Test
    void 저장소_조회_실패는_해당_프로젝트만_FAILED_로_돌려준다()
    {
        givenProjects(
                project("p1", "REQ-1", "svn://legacy.example.com/repo", null),   // 지원 구현 없음
                project("p2", "REQ-2", REPO, null));
        scm.commits = List.of(new ScmCommit("c1", "{REQ-2}"));
        scm.files = Map.of("c1", List.of("a.txt"));
        when(mapper.updateProjectFileNames(anyString(), anyString(), anyString())).thenReturn(1);

        RepoSyncResult result = service.syncFileNames(USER);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.items().getFirst().status()).isEqualTo(Status.FAILED);
        assertThat(result.items().getFirst().message()).contains("지원하지 않는 저장소");
    }

    @Test
    void 대상_조회에_사용자_조건과_페이징을_넣지_않는다()
    {
        givenProjects();
        service.syncFileNames(USER);

        verify(mapper).selectProjectMasterList(eq(new HashMap<>()));
    }

    @Test
    void 다른_사용자가_만든_프로젝트도_동기화하고_수정자에는_실행한_사용자를_남긴다()
    {
        ProjectMasterDTO others = project("p9", "REQ-1", REPO, null);
        others.setCreateUserId("someone-else");
        givenProjects(others);
        scm.commits = List.of(new ScmCommit("c1", "{REQ-1}"));
        scm.files = Map.of("c1", List.of("a.txt"));
        when(mapper.updateProjectFileNames(anyString(), anyString(), anyString())).thenReturn(1);

        assertThat(service.syncFileNames(USER).updated()).isEqualTo(1);
        verify(mapper).updateProjectFileNames("p9", "a.txt", USER);
    }

    private void givenProjects(ProjectMasterDTO... projects)
    {
        when(mapper.selectProjectMasterList(any())).thenReturn(List.of(projects));
    }

    private static ProjectMasterDTO project(String id, String reqId, String repoUrl, String fileNames)
    {
        ProjectMasterDTO p = new ProjectMasterDTO();
        p.setProjectId(id);
        p.setProjectName("project " + id);
        p.setReqId(reqId);
        p.setRepoUrl(repoUrl);
        p.setFileNames(fileNames);
        return p;
    }

    /** 저장소 종류를 바꿔 끼워도 동기화가 그대로 도는지 — ScmClient 계약만으로 검증한다. */
    private static class FakeScmClient implements ScmClient
    {
        List<ScmCommit> commits = List.of();
        Map<String, List<String>> files = Map.of();
        Instant since;
        int listCalls;
        List<String> fileCalls = new ArrayList<>();

        @Override
        public boolean supports(String repoUrl)
        {
            return repoUrl.startsWith("https://scm.example.com/");
        }

        @Override
        public List<ScmCommit> findCommitsSince(String repoUrl, Instant since)
        {
            if (!supports(repoUrl)) throw new ScmException("unexpected " + repoUrl);
            this.since = since;
            listCalls++;
            return commits;
        }

        @Override
        public List<String> findChangedFiles(String repoUrl, String commitId)
        {
            fileCalls.add(commitId);
            return files.getOrDefault(commitId, List.of());
        }
    }
}
