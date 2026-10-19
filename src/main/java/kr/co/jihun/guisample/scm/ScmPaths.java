package kr.co.jihun.guisample.scm;

import java.util.regex.Pattern;

/**
 * 저장소 경로 유틸 (저장소 종류와 무관).
 *
 * <p>저장소끌어오기 저장은 서버가 보낸 경로 그대로 사용자 PC 의 선택 폴더 아래에 파일을 만든다.
 * 그래서 선택 폴더 밖을 가리킬 수 있는 경로(절대경로, {@code ..}, 드라이브 표기 등)는 서버에서 먼저 걸러낸다.
 * 브라우저({@code repo-archive.js} 의 {@code isSafeSegment})도 같은 규칙으로 한 번 더 막는다.
 */
public final class ScmPaths
{
    /** 첫 세그먼트의 Windows 드라이브 표기 ({@code C:}). */
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private ScmPaths()
    {
    }

    /**
     * 안전한 상대경로면 그 값('/' 구분), 아니면 null.
     *
     * <p>거부: null·공백뿐, 맨 앞 '/', 역슬래시, NUL, 빈 세그먼트(연속 '/'), '.', '..',
     * 첫 세그먼트가 {@code C:} 같은 드라이브 표기, 맨 끝 '/'(디렉터리).
     */
    public static String safeRelativePath(String path)
    {
        if (path == null || path.isBlank())
        {
            return null;
        }
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
        {
            return null;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments)
        {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
            {
                return null;
            }
        }
        if (DRIVE.matcher(segments[0]).matches())
        {
            return null;
        }
        return path;
    }
}
