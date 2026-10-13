package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.ProjectMasterDTO;
import kr.co.jihun.guisample.dto.RepoSyncResult;
import kr.co.jihun.guisample.dto.RepoSyncResult.Item;
import kr.co.jihun.guisample.dto.RepoSyncResult.Status;
import kr.co.jihun.guisample.mapper.ProjectMasterMapper;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REPO동기화 — 저장소 커밋 메시지의 {@code {req_id}} 표식으로 프로젝트를 찾아
 * 그 커밋들이 바꾼 파일 경로를 {@code project_master.file_names} 에 반영한다.
 *
 * <p>저장소 종류를 모른다. 조회는 모두 {@link ScmClient} 를 거치며 구현은 {@link ScmClientResolver} 가
 * repo_url 로 고른다 — GitHub 외 저장소를 붙여도 이 클래스는 그대로다.
 *
 * <p><b>규칙</b>
 * <ul>
 *   <li>대상: repo_url·req_id 가 모두 있는 <b>전체</b> 프로젝트 — 소유자(사용자 이름)로 거르지 않는다.</li>
 *   <li>같은 repo_url 의 프로젝트는 커밋 목록을 한 번만 읽는다.</li>
 *   <li>커밋 메시지의 중괄호 안 값(앞뒤 공백 제거)이 req_id 와 <b>정확히</b> 같을 때 일치로 본다.
 *       한 메시지에 중괄호가 여럿이면 각각 비교한다.</li>
 *   <li>file_names 는 일치 커밋들의 변경 파일 합집합(중복 제거·정렬, 줄바꿈 구분)으로 <b>교체</b>한다.
 *       일치 커밋이 없으면 기존 값을 지우지 않고 그대로 둔다.</li>
 *   <li>한 저장소 조회가 실패해도 다른 저장소는 계속 처리한다.</li>
 * </ul>
 *
 * <p>트랜잭션을 걸지 않는다 — 외부 HTTP 호출 동안 DB 커넥션을 붙잡지 않도록, 프로젝트 단위로 바로 UPDATE 한다.
 */
@Slf4j
@Service
public class RepoSyncService
{
    /** 커밋 메시지의 {…} 표식. 중첩 중괄호는 받지 않는다. */
    private static final Pattern REQ_ID_MARKER = Pattern.compile("\\{([^{}]+)}");

    private final ProjectMasterMapper projectMasterMapper;
    private final ScmClientResolver scmClientResolver;
    private final int lookbackDays;
    private final Clock clock;

    /* 생성자가 둘이라(아래는 시각 고정용 테스트 생성자) 주입에 쓸 쪽을 명시한다. */
    @Autowired
    public RepoSyncService(ProjectMasterMapper projectMasterMapper,
                           ScmClientResolver scmClientResolver,
                           @Value("${scm.sync.lookback-days:30}") int lookbackDays)
    {
        this(projectMasterMapper, scmClientResolver, lookbackDays, Clock.systemUTC());
    }

    RepoSyncService(ProjectMasterMapper projectMasterMapper, ScmClientResolver scmClientResolver,
                    int lookbackDays, Clock clock)
    {
        this.projectMasterMapper = projectMasterMapper;
        this.scmClientResolver = scmClientResolver;
        this.lookbackDays = lookbackDays;
        this.clock = clock;
    }

    /**
     * @param userName 로그인 사용자명 — 갱신 행의 update_user_id 기록에만 쓰고 대상 선정에는 쓰지 않는다.
     *                 컨트롤러가 요청 스레드에서 {@code LoginUserSession} 으로 꺼내 넘긴다.
     */
    public RepoSyncResult syncFileNames(String userName)
    {
        /* 소유자 조건(createUserId)·페이징(pageSize) 없이 전체 프로젝트를 읽는다. */
        HashMap<String, Object> param = new HashMap<>();

        /* repo_url 별로 묶는다 (입력 순서 유지 → 결과 목록이 화면 목록 순서를 따른다). */
        Map<String, List<ProjectMasterDTO>> byRepo = new LinkedHashMap<>();
        for (ProjectMasterDTO project : projectMasterMapper.selectProjectMasterList(param))
        {
            if (StringUtils.hasText(project.getRepoUrl()) && StringUtils.hasText(project.getReqId()))
            {
                byRepo.computeIfAbsent(project.getRepoUrl().trim(), k -> new ArrayList<>()).add(project);
            }
        }

        Instant since = clock.instant().minus(Duration.ofDays(lookbackDays));
        List<Item> items = new ArrayList<>();
        byRepo.forEach((repoUrl, projects) -> items.addAll(syncRepository(repoUrl, projects, since, userName)));

        return new RepoSyncResult(
                lookbackDays,
                items.size(),
                count(items, Status.UPDATED),
                count(items, Status.UNCHANGED),
                count(items, Status.NO_MATCH),
                count(items, Status.FAILED),
                items);
    }

    private List<Item> syncRepository(String repoUrl, List<ProjectMasterDTO> projects, Instant since, String userName)
    {
        ScmClient client;
        Map<String, List<ScmCommit>> commitsByReqId;
        try
        {
            client = scmClientResolver.resolve(repoUrl);
            commitsByReqId = indexByReqId(client.findCommitsSince(repoUrl, since));
        }
        catch (RuntimeException e)
        {
            log.warn("REPO동기화 저장소 조회 실패 repo={} : {}", repoUrl, e.getMessage());
            return projects.stream().map(p -> failed(p, e)).toList();
        }

        /* 같은 커밋에 여러 프로젝트가 걸려도 파일 목록은 한 번만 읽는다. */
        Map<String, List<String>> filesByCommit = new HashMap<>();
        List<Item> items = new ArrayList<>();
        for (ProjectMasterDTO project : projects)
        {
            try
            {
                items.add(syncProject(client, repoUrl, project, commitsByReqId, filesByCommit, userName));
            }
            catch (RuntimeException e)
            {
                log.warn("REPO동기화 프로젝트 처리 실패 project={} : {}", project.getProjectId(), e.getMessage());
                items.add(failed(project, e));
            }
        }
        return items;
    }

    private Item syncProject(ScmClient client, String repoUrl, ProjectMasterDTO project,
                             Map<String, List<ScmCommit>> commitsByReqId,
                             Map<String, List<String>> filesByCommit, String userName)
    {
        List<ScmCommit> matched = commitsByReqId.getOrDefault(project.getReqId().trim(), List.of());
        if (matched.isEmpty())
        {
            return item(project, Status.NO_MATCH, 0, 0, null);
        }

        Set<String> files = new TreeSet<>();
        for (ScmCommit commit : matched)
        {
            files.addAll(filesByCommit.computeIfAbsent(commit.id(), id -> client.findChangedFiles(repoUrl, id)));
        }
        String fileNames = String.join("\n", files);

        if (Objects.equals(fileNames, project.getFileNames()))
        {
            return item(project, Status.UNCHANGED, matched.size(), files.size(), null);
        }
        int rows = projectMasterMapper.updateProjectFileNames(project.getProjectId(), fileNames, userName);
        if (rows == 0)
        {
            return item(project, Status.FAILED, matched.size(), files.size(), "갱신 대상 프로젝트를 찾을 수 없습니다(삭제됨).");
        }
        return item(project, Status.UPDATED, matched.size(), files.size(), null);
    }

    /** req_id → 그 표식을 가진 커밋들. 한 커밋에 같은 표식이 두 번 있어도 한 번만 넣는다. */
    static Map<String, List<ScmCommit>> indexByReqId(List<ScmCommit> commits)
    {
        Map<String, List<ScmCommit>> index = new HashMap<>();
        for (ScmCommit commit : commits)
        {
            for (String reqId : extractReqIds(commit.message()))
            {
                index.computeIfAbsent(reqId, k -> new ArrayList<>()).add(commit);
            }
        }
        return index;
    }

    /** 커밋 메시지에서 {…} 안의 값들을 꺼낸다 (앞뒤 공백 제거, 빈 값 제외, 중복 제거). */
    static Set<String> extractReqIds(String message)
    {
        Set<String> ids = new LinkedHashSet<>();
        if (message == null)
        {
            return ids;
        }
        Matcher m = REQ_ID_MARKER.matcher(message);
        while (m.find())
        {
            String id = m.group(1).trim();
            if (!id.isEmpty())
            {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Item failed(ProjectMasterDTO project, RuntimeException e)
    {
        return item(project, Status.FAILED, 0, 0, e.getMessage());
    }

    private static Item item(ProjectMasterDTO project, Status status, int commitCount, int fileCount, String message)
    {
        return new Item(project.getProjectId(), project.getProjectName(), project.getReqId(),
                status, commitCount, fileCount, message);
    }

    private static int count(List<Item> items, Status status)
    {
        return (int) items.stream().filter(i -> i.status() == status).count();
    }
}
