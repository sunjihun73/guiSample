package kr.co.jihun.guisample.dto;

/**
 * REPO PUSH 결과 ({@code POST /user/project/projects/{projectId}/repo-push} 응답의 result).
 *
 * @param projectId     푸시한 프로젝트
 * @param repoUrl       대상 저장소
 * @param path          저장소에 쓴 경로 ({@code src/docs/{project_id}.txt})
 * @param commitMessage 실제로 쓴 커밋 메시지
 * @param commitId      생성된 커밋 식별자 (Git: SHA)
 * @param created       새로 만든 파일이면 true, 기존 파일을 교체했으면 false
 */
public record RepoPushResult(String projectId, String repoUrl, String path, String commitMessage,
                             String commitId, boolean created)
{
}
