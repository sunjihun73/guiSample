package kr.co.jihun.guisample.dto;

/**
 * 저장소끌어오기 응답의 머리말 ({@code GET /user/repository/files} 응답의 summary).
 *
 * @param repoUrl       요청한 저장소 URL (앞뒤 공백 제거)
 * @param owner         URL 에서 꺼낸 소유자 이름 (꺼낼 수 없으면 null)
 * @param repo          URL 에서 꺼낸 저장소 이름 — 화면 경로 표시의 루트 (꺼낼 수 없으면 null)
 * @param branch        실제로 조회한 브랜치
 * @param defaultBranch 저장소 기본 브랜치
 * @param commitId      최신 커밋 SHA (전체 길이)
 * @param commitMessage 최신 커밋 메시지 전문
 * @param commitDate    최신 커밋 시각 ISO-8601 UTC 문자열 (예: {@code 2026-10-14T06:52:22Z}, 알 수 없으면 null)
 * @param truncated     저장소 쪽 또는 서버 상한(2만 행)으로 목록이 잘렸으면 true
 * @param entryCount    내려준 행 수 (= records)
 */
public record RepoTreeSummary(String repoUrl, String owner, String repo, String branch, String defaultBranch,
                              String commitId, String commitMessage, String commitDate,
                              boolean truncated, int entryCount)
{
}
