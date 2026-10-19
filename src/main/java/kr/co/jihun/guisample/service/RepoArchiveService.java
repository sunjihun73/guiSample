package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.scm.ScmArchiveVisitor;
import kr.co.jihun.guisample.scm.ScmClient;
import kr.co.jihun.guisample.scm.ScmClientResolver;
import kr.co.jihun.guisample.scm.ScmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 저장소끌어오기 저장 — 커밋 시점의 전체 파일을 프레임 스트림({@link RepoArchiveFrameWriter})으로 흘려보낸다.
 *
 * <p>파일은 <b>브라우저가 사용자 PC 의 선택 폴더에 쓴다</b>. 서버는 디스크에 쓰지 않는다(압축본은 메모리에서만 푼다).
 * 저장소 종류를 모르며 {@link ScmClientResolver} 가 URL 로 고른 {@link ScmClient#readArchive} 만 부른다.
 *
 * <p><b>소유권 해당 없음</b> — 앱 DB 를 읽거나 쓰지 않는다. 그래서 트랜잭션도 없다.
 * 상한(파일 수·파일 크기·합계)은 압축 폭탄·브라우저 메모리 방어용이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoArchiveService
{
    /** 파일 수 상한 — 목록 상한과 같다. */
    public static final int  MAX_FILES       = RepoBrowseService.MAX_ROWS;
    /** 파일 하나 상한 — GitHub 단일 파일 한도와 같다(프레임 u32 범위 안). */
    public static final long MAX_FILE_BYTES  = 100L * 1024 * 1024;
    /** 풀린 내용 합계 상한 — 압축 폭탄 방어. */
    public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;

    private final ScmClientResolver scmClientResolver;

    /**
     * 저장소 파일을 프레임으로 쓴다. MAGIC({@code start})과 끝 레코드({@code writeEnd}·{@code writeError})는 호출 측(컨트롤러)이 쓴다.
     *
     * @param repoUrl 저장소 URL (앞뒤 공백 무시)
     * @param ref     커밋 SHA 또는 브랜치명 (앞뒤 공백 무시)
     * @return 보낸 파일·건너뜀 개수와 내용 바이트 합계
     * @throws IllegalArgumentException URL·ref 가 비었을 때 (저장소를 부르지 않는다)
     * @throws ScmException 지원하지 않는 URL, 저장소·커밋 없음, 한도·상한 초과 등
     * @throws IOException 응답 쓰기 실패(브라우저 연결 끊김 등)
     */
    public Result streamArchive(String repoUrl, String ref, RepoArchiveFrameWriter writer) throws IOException
    {
        if (!StringUtils.hasText(repoUrl))
        {
            throw new IllegalArgumentException("저장소 URL 을 입력하세요");
        }
        if (!StringUtils.hasText(ref))
        {
            throw new IllegalArgumentException("커밋 ID 가 없습니다. 먼저 목록을 끌어오세요");
        }
        String url = repoUrl.trim();
        ScmClient client = scmClientResolver.resolve(url);

        Counter counter = new Counter(writer);
        client.readArchive(url, ref.trim(), counter);
        return new Result(counter.fileCount, counter.skippedCount, counter.totalBytes);
    }

    /**
     * 스트림을 {@code max} 바이트까지 읽는다 (순수). 넘으면 {@link ScmException}.
     */
    static byte[] readLimited(InputStream in, long max) throws IOException
    {
        int limit = (int) Math.min(max + 1, Integer.MAX_VALUE - 8);
        byte[] bytes = in.readNBytes(limit);
        if (bytes.length > max)
        {
            throw new ScmException("파일 크기가 상한(" + (max / 1024 / 1024) + " MiB)을 넘습니다");
        }
        return bytes;
    }

    /** visitor — 상한을 확인하며 프레임을 쓰고 개수를 센다. */
    private static final class Counter implements ScmArchiveVisitor
    {
        private final RepoArchiveFrameWriter writer;
        private int fileCount;
        private int skippedCount;
        private long totalBytes;

        private Counter(RepoArchiveFrameWriter writer)
        {
            this.writer = writer;
        }

        @Override
        public void onFile(String path, InputStream content) throws IOException
        {
            if (fileCount >= MAX_FILES)
            {
                throw new ScmException("파일 수가 상한(" + MAX_FILES + "개)을 넘어 중단했습니다");
            }
            byte[] bytes;
            try
            {
                bytes = readLimited(content, MAX_FILE_BYTES);
            }
            catch (ScmException e)
            {
                throw new ScmException(e.getMessage() + ": " + path, e);
            }
            if (totalBytes + bytes.length > MAX_TOTAL_BYTES)
            {
                throw new ScmException("저장소 내용 합계가 상한(" + (MAX_TOTAL_BYTES / 1024 / 1024) + " MiB)을 넘어 중단했습니다");
            }
            writer.writeFile(path, bytes);
            fileCount++;
            totalBytes += bytes.length;
        }

        @Override
        public void onSkipped(String path, String reason) throws IOException
        {
            writer.writeSkipped(path, reason);
            skippedCount++;
        }
    }

    /** {@link #streamArchive} 결과 — 끝 레코드 'Z' 의 값. */
    public record Result(int fileCount, int skippedCount, long totalBytes)
    {
    }
}
