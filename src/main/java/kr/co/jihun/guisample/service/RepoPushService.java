package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import kr.co.jihun.guisample.dto.RepoPushResult;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmPushResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * REPO PUSH — {@code project_master.project_description}(HTML)을 프로젝트의 repo_url 저장소에
 * {@code src/docs/{project_id}.txt} 로 커밋한다.
 *
 * <p>저장소 종류를 모른다. 쓰기도 조회와 같이 {@link ScmClient} 를 거치며 구현은
 * {@link ScmClientResolver} 가 repo_url 로 고른다 — 푸시를 지원하지 않는 구현이면
 * {@code putFile} 기본 구현이 예외를 던진다.
 *
 * <p><b>REPO동기화와 달리 소유권 규칙을 그대로 따른다</b> — 화면의 프로젝트 팝업에서 한 건씩
 * 실행하는 기능이라 자기 프로젝트만 대상이다. 조회는 소유자 조건이 걸린 단건 조회를 쓴다.
 *
 * <p>트랜잭션을 걸지 않는다 — 외부 HTTP 를 기다리는 동안 DB 커넥션을 붙잡지 않도록 읽고 끝낸다.
 * 앱 DB 는 바뀌지 않는다(푸시는 저장소 쪽 변경뿐).
 */
@Slf4j
@Service
public class RepoPushService
{
    /** 저장소 안의 대상 폴더. */
    private static final String DOCS_DIR = "src/docs/";

    /** 커밋 메시지 접두어 — 뒤에 실행 날짜를 붙인다. */
    private static final String COMMIT_MESSAGE_PREFIX = "프로젝트 설명 ";

    private static final DateTimeFormatter COMMIT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ProjectMasterService projectMasterService;
    private final ScmClientResolver scmClientResolver;
    private final Clock clock;

    /* 생성자가 둘이라(아래는 날짜 고정용 테스트 생성자) 주입에 쓸 쪽을 명시한다. */
    @Autowired
    public RepoPushService(ProjectMasterService projectMasterService, ScmClientResolver scmClientResolver)
    {
        this(projectMasterService, scmClientResolver, Clock.systemDefaultZone());
    }

    RepoPushService(ProjectMasterService projectMasterService, ScmClientResolver scmClientResolver, Clock clock)
    {
        this.projectMasterService = projectMasterService;
        this.scmClientResolver = scmClientResolver;
        this.clock = clock;
    }

    /**
     * 프로젝트 설명을 저장소에 푸시한다.
     *
     * @param projectId 대상 프로젝트
     * @param userName  로그인 사용자명(user_master.user_name) — 소유자 조건
     * @return 푸시 결과 (경로·커밋 메시지·커밋 id·신규 생성 여부)
     * @throws IllegalArgumentException 남의 프로젝트이거나 없을 때, repo_url·설명이 비었을 때
     * @throws kr.co.jihun.guisample.scm.ScmException 저장소 호출 실패
     */
    public RepoPushResult pushProjectDescription(String projectId, String userName)
    {
        ProjectMasterDTO project = projectMasterService.selectProjectMaster(projectId, userName);
        if (project == null)
        {
            throw new IllegalArgumentException("푸시 대상 프로젝트를 찾을 수 없습니다.");
        }
        if (!StringUtils.hasText(project.getRepoUrl()))
        {
            throw new IllegalArgumentException("저장소 URL 이 비어 있습니다. 저장소 URL 을 먼저 입력하세요.");
        }
        /* 빈 파일을 올려 기존 문서를 지워 버리지 않도록 막는다. */
        if (!StringUtils.hasText(project.getProjectDescription()))
        {
            throw new IllegalArgumentException("프로젝트 설명이 비어 있어 푸시할 내용이 없습니다.");
        }

        String repoUrl = project.getRepoUrl().trim();
        String path    = DOCS_DIR + project.getProjectId() + ".txt";
        String message = COMMIT_MESSAGE_PREFIX + LocalDate.now(clock).format(COMMIT_DATE);

        ScmClient client = scmClientResolver.resolve(repoUrl);
        ScmPushResult result = client.putFile(repoUrl, path, project.getProjectDescription(), message);

        log.info("REPO PUSH project={} repo={} path={} commit={} created={}",
                project.getProjectId(), repoUrl, result.path(), result.commitId(), result.created());

        return new RepoPushResult(project.getProjectId(), repoUrl, result.path(), message,
                result.commitId(), result.created());
    }
}
