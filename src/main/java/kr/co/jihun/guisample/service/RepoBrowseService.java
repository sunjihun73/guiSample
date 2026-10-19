package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.RepoFileRow;
import kr.co.jihun.guisample.dto.RepoTreeSummary;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmEntryType;
import kr.co.jihun.guisample.scm.ScmTree;
import kr.co.jihun.guisample.scm.ScmTreeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 저장소끌어오기 — 저장소 URL·브랜치로 최신 커밋의 파일 트리를 읽어 그리드 행으로 바꾼다.
 *
 * <p>서버 디스크에 clone/pull 하지 않는다. 저장소 종류를 모르며 {@link ScmClientResolver} 가
 * URL 로 고른 {@link ScmClient#findTree} 만 부른다. 지원하지 않는 URL 은 외부 호출 전에 거부된다.
 *
 * <p><b>소유권 해당 없음</b> — 앱 DB 를 읽거나 쓰지 않는다. 그래서 트랜잭션도 없다
 * (외부 HTTP 를 기다리는 동안 커넥션을 잡지 않는다).
 *
 * <p>전체 트리를 한 번에 내려주고 폴더 이동은 화면이 처리한다 — 폴더마다 저장소를 다시 부르면 호출 한도를 태운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoBrowseService
{
    /** 응답 행 상한 — 브라우저 메모리·응답 크기 방어용. 넘으면 잘라내고 truncated 로 알린다. */
    public static final int MAX_ROWS = 20_000;

    private final ScmClientResolver scmClientResolver;

    /**
     * 저장소 파일 트리를 읽는다.
     *
     * @param repoUrl 저장소 URL (앞뒤 공백 무시)
     * @param branch  브랜치. null·빈 값이면 기본 브랜치
     * @return 머리말과 정렬된 행 전체
     * @throws IllegalArgumentException URL 이 비었을 때
     * @throws kr.co.jihun.guisample.scm.ScmException 지원하지 않는 URL, 저장소·브랜치 없음, 한도 초과 등
     */
    public Result browse(String repoUrl, String branch)
    {
        if (!StringUtils.hasText(repoUrl))
        {
            throw new IllegalArgumentException("저장소 URL 을 입력하세요");
        }
        String url = repoUrl.trim();
        String targetBranch = StringUtils.hasText(branch) ? branch.trim() : null;

        ScmClient client = scmClientResolver.resolve(url);
        ScmTree tree = client.findTree(url, targetBranch);

        Rows rows = toRows(tree.entries(), MAX_ROWS);
        String[] ownerRepo = ownerAndRepo(url);

        RepoTreeSummary summary = new RepoTreeSummary(
                url, ownerRepo[0], ownerRepo[1],
                tree.branch(), tree.defaultBranch(),
                tree.commitId(), tree.commitMessage(),
                tree.commitDate() == null ? null : tree.commitDate().toString(),
                tree.truncated() || rows.truncated(),
                rows.rows().size());

        log.info("저장소끌어오기 repo={} branch={} commit={} rows={} truncated={}",
                url, summary.branch(), summary.commitId(), summary.entryCount(), summary.truncated());
        return new Result(summary, rows.rows());
    }

    /**
     * 트리 항목 → 그리드 행 (순수 함수).
     *
     * <p>name = 마지막 세그먼트, parentPath = 마지막 '/' 앞(없으면 ""), depth = '/' 개수.
     * 정렬: parentPath → 폴더(DIR·SUBMODULE) 먼저 → name 대소문자 무시 → name.
     * {@code maxRows} 를 넘으면 정렬된 앞부분만 남기고 truncated=true.
     */
    static Rows toRows(List<ScmTreeEntry> entries, int maxRows)
    {
        List<RepoFileRow> rows = new ArrayList<>();
        if (entries != null)
        {
            for (ScmTreeEntry entry : entries)
            {
                String path = entry.path().startsWith("/") ? entry.path().substring(1) : entry.path();
                int slash = path.lastIndexOf('/');
                String name   = slash < 0 ? path : path.substring(slash + 1);
                String parent = slash < 0 ? "" : path.substring(0, slash);
                int depth = (int) path.chars().filter(c -> c == '/').count();
                Long size = entry.type() == ScmEntryType.FILE ? entry.size() : null;
                rows.add(new RepoFileRow(path, name, parent, entry.type().name(), size, depth, entry.webUrl()));
            }
        }

        rows.sort(Comparator.comparing(RepoFileRow::parentPath)
                .thenComparing(row -> isFolder(row) ? 0 : 1)
                .thenComparing(RepoFileRow::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RepoFileRow::name));

        if (rows.size() > maxRows)
        {
            return new Rows(List.copyOf(rows.subList(0, maxRows)), true);
        }
        return new Rows(rows, false);
    }

    private static boolean isFolder(RepoFileRow row)
    {
        return ScmEntryType.DIR.name().equals(row.type()) || ScmEntryType.SUBMODULE.name().equals(row.type());
    }

    /**
     * 표시용 owner/repo — 호스트 다음 두 경로 이름({@code .git} 제거).
     * {@code https://host/o/r(.git)}, {@code git@host:o/r(.git)} 를 받는다. 꺼낼 수 없으면 null.
     */
    static String[] ownerAndRepo(String repoUrl)
    {
        String url = repoUrl.trim();
        int query = indexOfAny(url, '?', '#');
        if (query >= 0)
        {
            url = url.substring(0, query);
        }
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(".git"))
        {
            url = url.substring(0, url.length() - 4);
        }
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url.replaceFirst("^[^@/]+@", "").replace(':', '/');
        String[] parts = rest.split("/");
        if (parts.length < 3)
        {
            return new String[] { null, null };
        }
        return new String[] { parts[1], parts[2] };
    }

    private static int indexOfAny(String s, char a, char b)
    {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /** 정렬·상한 적용 결과. */
    record Rows(List<RepoFileRow> rows, boolean truncated)
    {
    }

    /** {@link #browse} 결과 — 응답의 summary 와 rows. */
    public record Result(RepoTreeSummary summary, List<RepoFileRow> rows)
    {
    }
}
