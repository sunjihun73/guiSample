package kr.co.jihun.guisample.scm;

/**
 * 저장소 종류와 무관한 커밋 요약.
 *
 * @param id      커밋 식별자 (Git: SHA, SVN: 리비전 번호)
 * @param message 커밋 메시지 전문
 */
public record ScmCommit(String id, String message)
{
}
