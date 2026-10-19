package kr.co.jihun.guisample.controller.user;

import jakarta.servlet.http.HttpServletResponse;
import kr.co.jihun.guisample.service.RepoArchiveFrameWriter;
import kr.co.jihun.guisample.service.RepoArchiveService;
import kr.co.jihun.guisample.service.RepoBrowseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
    private final RepoArchiveService repoArchiveService;

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

    /**
     * 커밋 시점의 전체 파일을 바이트 프레임 스트림으로 내린다 — 브라우저가 받아 사용자 PC 의 선택 폴더에 쓴다.
     * 형식은 {@link RepoArchiveFrameWriter} 참조.
     *
     * <p><b>항상 HTTP 200</b> 이며 실패도 프레임('E')이다 — 응답 형식을 하나로 두어 브라우저 파서가 하나다.
     * MAGIC 을 먼저 쓰고 flush 한 뒤 저장소를 부르므로, 그 뒤에 예외를 밖으로 던지면 커밋된 응답 뒤에
     * 오류 페이지가 섞인다. 그래서 <b>어떤 예외도 밖으로 던지지 않는다</b>(SSE 경로와 같은 원칙).
     *
     * <p>동기 MVC 로 처리한다({@code StreamingResponseBody}·{@code boundedElastic} 아님) — 비동기 디스패치 타임아웃과
     * 스레드 바인딩 문제를 피한다. 읽기 전용이라 GET(CSRF 불필요). {@code LoginUserSession} 을 쓰지 않는다(소유권 해당 없음).
     */
    @GetMapping("/archive")
    public void archive(@RequestParam(required = false) String repoUrl,
                        @RequestParam(required = false) String ref,
                        HttpServletResponse response)
    {
        long startedAt = System.currentTimeMillis();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/octet-stream");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        RepoArchiveFrameWriter writer;
        try
        {
            writer = new RepoArchiveFrameWriter(response.getOutputStream());
            writer.start();
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("저장소 파일 내려받기 응답 시작 실패 repo={} ref={}: {}", repoUrl, ref, e.getMessage());
            return;
        }

        try
        {
            RepoArchiveService.Result result = repoArchiveService.streamArchive(repoUrl, ref, writer);
            writer.writeEnd(result.fileCount(), result.skippedCount(), result.totalBytes());
            log.info("저장소 파일 내려받기 repo={} ref={} files={} skipped={} bytes={} elapsedMs={}",
                    repoUrl, ref, result.fileCount(), result.skippedCount(), result.totalBytes(),
                    System.currentTimeMillis() - startedAt);
        }
        catch (Exception e)
        {
            log.warn("저장소 파일 내려받기 실패 repo={} ref={} elapsedMs={}: {}",
                    repoUrl, ref, System.currentTimeMillis() - startedAt, e.getMessage());
            try
            {
                /* message 는 화면이 그대로 경고창에 쓴다 — 메시지 없는 예외(NPE 등)도 문자열을 보장한다. */
                writer.writeError(e.getMessage() != null ? e.getMessage() : "저장소 파일을 가져오지 못했습니다.");
            }
            catch (IOException | RuntimeException writeFailure)
            {
                /* 브라우저가 연결을 끊음(ClientAbortException 등) — 더 알릴 곳이 없다. */
                log.debug("저장소 파일 내려받기 오류 프레임 쓰기 실패 repo={}: {}", repoUrl, writeFailure.getMessage());
            }
        }
    }
}
