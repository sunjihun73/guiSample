package kr.co.jihun.guisample.scm;

import java.time.Instant;
import java.util.List;

/**
 * 브랜치 최신 커밋의 전체 파일 트리 ({@link ScmClient#findTree}).
 *
 * <p>저장소 종류와 무관한 값 객체다. 저장소 이름·소유자처럼 URL 에서 얻을 수 있는 표시용 값은
 * 넣지 않는다 — 필요한 쪽(서비스)이 URL 에서 꺼낸다.
 *
 * @param branch        실제로 조회한 브랜치 (요청이 비었으면 기본 브랜치)
 * @param defaultBranch 저장소의 기본 브랜치
 * @param commitId      브랜치 최신 커밋 식별자 (Git: SHA)
 * @param commitMessage 최신 커밋 메시지 전문
 * @param commitDate    최신 커밋 시각 (알 수 없으면 null)
 * @param truncated     저장소 쪽이 항목을 다 돌려주지 못했으면 true (GitHub: 10만 항목/7MB 초과)
 * @param entries       트리 항목 (정렬 보장 없음)
 */
public record ScmTree(String branch, String defaultBranch, String commitId, String commitMessage,
                      Instant commitDate, boolean truncated, List<ScmTreeEntry> entries)
{
}
