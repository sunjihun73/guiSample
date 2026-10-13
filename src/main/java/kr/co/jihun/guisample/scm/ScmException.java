package kr.co.jihun.guisample.scm;

/** 저장소 조회 실패 (지원하지 않는 URL, 인증 실패, 레이트 리밋, 네트워크 오류 등). */
public class ScmException extends RuntimeException
{
    public ScmException(String message)
    {
        super(message);
    }

    public ScmException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
