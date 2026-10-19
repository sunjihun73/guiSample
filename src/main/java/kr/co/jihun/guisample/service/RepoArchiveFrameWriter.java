package kr.co.jihun.guisample.service;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 저장소끌어오기 저장 응답({@code GET /user/repository/archive})의 바이트 프레임 작성기 (빈 아님, 순수).
 *
 * <p>브라우저({@code static/js/repo-archive.js})와 e2e({@code buildFrames})가 <b>이 형식에 그대로 의존한다</b>.
 * 바꾸면 세 곳을 함께 고쳐야 한다. 빅엔디언({@link DataOutputStream} 기준):
 *
 * <pre>
 * stream := MAGIC record*            MAGIC = 'G' 'S' 'R' 'A' 0x01 (버전 1)
 * record := 'F' str(path) u32(n) bytes[n]          파일 1개 (저장소 루트 기준 상대경로, 저장소명 미포함)
 *         | 'S' str(path) str(reason)              건너뛴 항목
 *         | 'E' str(message)                       실패 — 마지막 레코드
 *         | 'Z' u32(fileCount) u32(skippedCount) u64(totalBytes)   정상 종료 — 마지막 레코드
 * str    := u32(byteLen) UTF-8 바이트
 * </pre>
 *
 * <p>레코드마다 flush 한다 — 브라우저가 받는 대로 파일을 쓴다. 전달받은 스트림은 닫지 않는다(소유자는 호출 측).
 */
public class RepoArchiveFrameWriter
{
    /** 스트림 머리 — 'GSRA' + 버전 1. 브라우저는 이것이 맞지 않으면(로그인 HTML 등) 실패로 본다. */
    static final byte[] MAGIC = { 'G', 'S', 'R', 'A', 0x01 };

    static final byte TYPE_FILE    = 'F';
    static final byte TYPE_SKIPPED = 'S';
    static final byte TYPE_ERROR   = 'E';
    static final byte TYPE_END     = 'Z';

    private final DataOutputStream out;

    /** 생성 시에는 아무것도 쓰지 않는다. */
    public RepoArchiveFrameWriter(OutputStream out)
    {
        this.out = new DataOutputStream(out);
    }

    /** MAGIC 을 쓰고 flush — 응답 헤더가 바로 나가고 이후 모든 결과는 프레임으로만 나간다. */
    public void start() throws IOException
    {
        out.write(MAGIC);
        out.flush();
    }

    public void writeFile(String path, byte[] content) throws IOException
    {
        out.writeByte(TYPE_FILE);
        writeString(path);
        out.writeInt(content.length);
        out.write(content);
        out.flush();
    }

    public void writeSkipped(String path, String reason) throws IOException
    {
        out.writeByte(TYPE_SKIPPED);
        writeString(path);
        writeString(reason);
        out.flush();
    }

    public void writeEnd(int fileCount, int skippedCount, long totalBytes) throws IOException
    {
        out.writeByte(TYPE_END);
        out.writeInt(fileCount);
        out.writeInt(skippedCount);
        out.writeLong(totalBytes);
        out.flush();
    }

    public void writeError(String message) throws IOException
    {
        out.writeByte(TYPE_ERROR);
        writeString(message);
        out.flush();
    }

    /** u32(byteLen) + UTF-8. null 은 빈 문자열로 쓴다. ({@code writeUTF} 는 수정 UTF-8·u16 길이라 쓰지 않는다.) */
    private void writeString(String value) throws IOException
    {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
