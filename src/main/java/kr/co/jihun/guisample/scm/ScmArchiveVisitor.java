package kr.co.jihun.guisample.scm;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link ScmClient#readArchive} 가 파일을 하나씩 넘겨주는 콜백 (저장소끌어오기 저장).
 *
 * <p>구현(저장소 쪽)은 경로를 {@link ScmPaths#safeRelativePath} 로 검사한 뒤 통과한 것만 {@link #onFile} 로,
 * 나머지는 {@link #onSkipped} 로 넘긴다. 디렉터리 항목은 어느 쪽으로도 넘기지 않는다.
 */
public interface ScmArchiveVisitor
{
    /**
     * 파일 1개.
     *
     * @param path    저장소 루트 기준 상대경로('/' 구분, 저장소명 미포함)
     * @param content 이 항목의 내용만 읽히는 스트림. 닫지 않아도 되며 닫아도 다음 항목에 영향이 없다(구현이 관리)
     */
    void onFile(String path, InputStream content) throws IOException;

    /**
     * 규칙 위반 등으로 건너뛴 항목.
     *
     * @param path   아카이브 안의 경로 (최상위 폴더를 뗄 수 있으면 뗀 값)
     * @param reason 사람이 읽을 사유
     */
    default void onSkipped(String path, String reason) throws IOException
    {
    }
}
