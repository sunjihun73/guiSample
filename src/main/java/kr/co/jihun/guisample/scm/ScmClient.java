package kr.co.jihun.guisample.scm;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 소스 저장소(SCM) 조회 계약.
 *
 * <p>REPO동기화·REPO PUSH·저장소끌어오기(목록·PC 폴더 저장)는 이 인터페이스만 보고 동작한다. 저장소 종류(GitHub, GitLab, SVN …)를 늘릴 때는
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

    /**
     * 파일 하나를 기본 브랜치에 커밋한다 (없으면 생성, 있으면 내용 교체).
     *
     * <p>조회만 지원하는 구현(읽기 전용 미러 등)은 재정의하지 않는다 — 기본 구현이
     * {@link ScmException} 을 던지므로 호출 측은 "지원하지 않는 저장소"로 같게 처리할 수 있다.
     *
     * @param path          저장소 루트 기준 상대 경로 (예: {@code src/docs/foo.txt})
     * @param content       파일 내용 (UTF-8 로 저장된다)
     * @param commitMessage 커밋 메시지
     * @return 푸시 결과 (생성/갱신 여부와 커밋 식별자)
     */
    default ScmPushResult putFile(String repoUrl, String path, String content, String commitMessage)
    {
        throw new ScmException("이 저장소는 파일 푸시를 지원하지 않습니다: " + repoUrl);
    }

    /**
     * 브랜치 최신 커밋의 전체 파일 트리 (저장소끌어오기 화면).
     *
     * <p>{@link #putFile} 과 같은 이유로 {@code default} 다 — 트리 조회를 구현하지 않은 저장소는
     * 기본 구현이 {@link ScmException} 을 던지고, 기존 구현체는 고칠 필요가 없다.
     *
     * @param branch 조회할 브랜치. null 이거나 비었으면 저장소의 기본 브랜치
     * @return 트리 (실제 사용한 브랜치·최신 커밋 요약 포함)
     */
    default ScmTree findTree(String repoUrl, String branch)
    {
        throw new ScmException("이 저장소는 파일 목록 조회를 지원하지 않습니다: " + repoUrl);
    }

    /**
     * 커밋(또는 브랜치) 시점의 파일 전체를 하나씩 visitor 에 넘긴다 (저장소끌어오기 저장 — 브라우저 PC 폴더로 내려받기).
     *
     * <p>경로는 저장소 루트 기준 상대경로이며 {@link ScmPaths#safeRelativePath} 를 통과한 것만
     * {@link ScmArchiveVisitor#onFile} 로 온다. 구현은 서버 디스크에 쓰지 않는다.
     * {@link #putFile} 과 같은 이유로 {@code default} 다 — 구현하지 않은 저장소는 {@link ScmException}.
     *
     * @param ref 커밋 SHA 또는 브랜치명 (화면은 트리 조회 응답의 커밋 SHA 를 보낸다)
     */
    default void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException
    {
        throw new ScmException("이 저장소는 파일 내려받기를 지원하지 않습니다: " + repoUrl);
    }
}
