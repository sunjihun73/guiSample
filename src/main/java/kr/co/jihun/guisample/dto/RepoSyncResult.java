package kr.co.jihun.guisample.dto;

import java.util.List;

/**
 * REPO동기화 결과 ({@code POST /user/project/projects/repo-sync} 응답 본문).
 *
 * @param lookbackDays 조회한 기간(일)
 * @param total        동기화 대상 프로젝트 수 (repo_url·req_id 가 모두 있는 로그인 사용자의 프로젝트)
 * @param updated      file_names 를 갱신한 건수
 * @param unchanged    일치 커밋은 있으나 파일 목록이 기존 값과 같아 갱신하지 않은 건수
 * @param noMatch      기간 내 req_id 가 일치하는 커밋이 없어 손대지 않은 건수
 * @param failed       저장소 조회 실패 건수
 * @param items        프로젝트별 결과
 */
public record RepoSyncResult(int lookbackDays, int total, int updated, int unchanged, int noMatch, int failed,
                             List<Item> items)
{
    public enum Status { UPDATED, UNCHANGED, NO_MATCH, FAILED }

    /**
     * @param commitCount req_id 가 일치한 커밋 수
     * @param fileCount   추출된 파일 수 (중복 제거 후)
     * @param message     실패 사유 (FAILED 일 때만)
     */
    public record Item(String projectId, String projectName, String reqId, Status status,
                       int commitCount, int fileCount, String message)
    {
    }
}
