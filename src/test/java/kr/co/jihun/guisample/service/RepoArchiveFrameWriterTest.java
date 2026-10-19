package kr.co.jihun.guisample.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프레임 형식 회귀 — 브라우저(repo-archive.js)와 e2e(buildFrames)가 이 바이트열에 의존한다.
 */
class RepoArchiveFrameWriterTest
{
    @Test
    void 생성만으로는_아무것도_쓰지_않고_start_가_MAGIC_을_쓴다() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RepoArchiveFrameWriter writer = new RepoArchiveFrameWriter(out);
        assertThat(out.size()).isZero();

        writer.start();

        assertThat(out.toByteArray()).containsExactly(0x47, 0x53, 0x52, 0x41, 0x01);
    }

    @Test
    void 파일_건너뜀_끝_레코드를_빅엔디언으로_쓴다() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RepoArchiveFrameWriter writer = new RepoArchiveFrameWriter(out);
        byte[] binary = { 0, (byte) 0xFF, 0x7F, (byte) 0x80 };

        writer.start();
        writer.writeFile("한글/파일.md", "내용".getBytes(StandardCharsets.UTF_8));
        writer.writeFile("empty.txt", new byte[0]);
        writer.writeFile("bin/x.dat", binary);
        writer.writeSkipped("../evil.txt", "허용되지 않는 경로");
        writer.writeEnd(3, 1, 10_000_000_000L);

        byte[] bytes = out.toByteArray();
        /* 첫 레코드 길이 접두가 빅엔디언인지 바이트로 직접 확인 — '한글/파일.md' 는 UTF-8 16바이트. */
        assertThat(bytes[5]).isEqualTo((byte) 'F');
        assertThat(new byte[] { bytes[6], bytes[7], bytes[8], bytes[9] }).containsExactly(0, 0, 0, 16);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertThat(in.readNBytes(5)).containsExactly('G', 'S', 'R', 'A', 1);

        assertThat(in.readByte()).isEqualTo((byte) 'F');
        assertThat(readString(in)).isEqualTo("한글/파일.md");
        assertThat(in.readNBytes(in.readInt())).isEqualTo("내용".getBytes(StandardCharsets.UTF_8));

        assertThat(in.readByte()).isEqualTo((byte) 'F');
        assertThat(readString(in)).isEqualTo("empty.txt");
        assertThat(in.readInt()).isZero();

        assertThat(in.readByte()).isEqualTo((byte) 'F');
        assertThat(readString(in)).isEqualTo("bin/x.dat");
        assertThat(in.readNBytes(in.readInt())).isEqualTo(binary);

        assertThat(in.readByte()).isEqualTo((byte) 'S');
        assertThat(readString(in)).isEqualTo("../evil.txt");
        assertThat(readString(in)).isEqualTo("허용되지 않는 경로");

        assertThat(in.readByte()).isEqualTo((byte) 'Z');
        assertThat(in.readInt()).isEqualTo(3);
        assertThat(in.readInt()).isEqualTo(1);
        assertThat(in.readLong()).isEqualTo(10_000_000_000L);
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void 오류_레코드는_E_와_메시지다() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RepoArchiveFrameWriter writer = new RepoArchiveFrameWriter(out);

        writer.start();
        writer.writeError("저장소를 찾을 수 없음");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        in.skipNBytes(5);
        assertThat(in.readByte()).isEqualTo((byte) 'E');
        assertThat(readString(in)).isEqualTo("저장소를 찾을 수 없음");
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void null_문자열은_길이_0_으로_쓴다() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RepoArchiveFrameWriter(out).writeError(null);

        assertThat(out.toByteArray()).containsExactly('E', 0, 0, 0, 0);
    }

    private static String readString(DataInputStream in) throws IOException
    {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }
}
