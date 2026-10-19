package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.service.RepoBrowseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장소끌어오기 REST 엔드포인트.
 *
 * <p><b>소유권 해당 없음</b> — 앱 DB 의 어떤 행도 읽거나 쓰지 않으므로 {@code LoginUserSession} 을 쓰지 않는다.
 * 인증은 {@code /user/**} 규칙으로 {@code SecurityConfig} 가 강제한다.
 */
@Slf4j
@RestController
@RequestMapping("/user/repository")
@RequiredArgsConstructor
public class RepositoryRestController
{
    private final RepoBrowseService repoBrowseService;

    /**
     * 저장소 브랜치 최신 커밋의 전체 파일 트리 — jqGrid shape 로 한 번에 내린다(page=1, total=1).
     *
     * <p>모든 실패(URL 누락·지원하지 않는 URL·저장소/브랜치 없음·한도 초과)는 HTTP 200 +
     * {@code success:false} + 빈 jqGrid shape 로 돌려준다 — 화면이 실수로 그리드에 넣어도 깨지지 않게
     * shape 키를 유지한다. {@code repoUrl} 도 required=false 로 받아 누락 시 400 대신 같은 형태로 답한다.
     */
    @GetMapping("/files")
    public Map<String, Object> files(@RequestParam(required = false) String repoUrl,
                                     @RequestParam(required = false) String branch)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            RepoBrowseService.Result browse = repoBrowseService.browse(repoUrl, branch);
            result.put("success", true);
            result.put("page",    1);
            result.put("total",   1);
            result.put("records", browse.rows().size());
            result.put("rows",    browse.rows());
            result.put("summary", browse.summary());
        }
        catch (Exception e)
        {
            log.warn("저장소끌어오기 실패 repo={} branch={}: {}", repoUrl, branch, e.getMessage());
            result.put("success", false);
            /* message 는 화면이 그대로 경고창에 쓴다 — 메시지 없는 예외(NPE 등)도 문자열을 보장한다. */
            result.put("message", e.getMessage() != null ? e.getMessage() : "저장소 목록을 가져오지 못했습니다.");
            result.put("page",    1);
            result.put("total",   0);
            result.put("records", 0);
            result.put("rows",    List.of());
        }
        return result;
    }
}
