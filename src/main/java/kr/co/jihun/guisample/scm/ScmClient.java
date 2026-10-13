package kr.co.jihun.guisample.scm;

import java.time.Instant;
import java.util.List;

/**
 * 소스 저장소(SCM) 조회 계약.
 *
 * <p>REPO동기화는 이 인터페이스만 보고 동작한다. 저장소 종류(GitHub, GitLab, SVN …)를 늘릴 때는
 * 구현체를 하나 더 빈으로 등록하면 되고, {@link ScmClientResolver} 가 {@link #supports} 로 골라 쓴다.
 * 동기화 로직({@code RepoSyncService})은 고칠 필요가 없다.
 *
 * <p>커밋 목록과 변경 파일을 나눈 이유 — GitHub 처럼 목록 API 에 파일이 없는 저장소는 req_id 가
 * 일치한 커밋만 파일을 따로 읽어 호출 수를 줄인다. SVN 처럼 {@code log -v} 한 번에 파일까지 오는
 * 저장소는 목록 조회 때 읽어 둔 값을 {@link #findChangedFiles} 에서 돌려주면 된다.
 */
public interface ScmClient
{
    /** 이 구현이 처리할 수 있는 저장소 URL 인지. */
    boolean supports(String repoUrl);

    /** {@code since} 이후의 커밋 목록 (기본 브랜치/트렁크 기준). */
    List<ScmCommit> findCommitsSince(String repoUrl, Instant since);

    /** 커밋에서 변경된 파일 경로 목록 (저장소 루트 기준 상대 경로). */
    List<String> findChangedFiles(String repoUrl, String commitId);
}
