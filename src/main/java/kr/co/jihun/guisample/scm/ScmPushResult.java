package kr.co.jihun.guisample.scm;

/**
 * 파일 푸시 결과 ({@link ScmClient#putFile}).
 *
 * @param path     저장소에 쓴 경로 (루트 기준 상대 경로)
 * @param commitId 생성된 커밋 식별자 (Git: SHA)
 * @param created  새로 만든 파일이면 true, 기존 파일을 교체했으면 false
 */
public record ScmPushResult(String path, String commitId, boolean created)
{
}
