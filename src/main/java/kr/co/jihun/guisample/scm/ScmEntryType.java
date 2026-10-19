package kr.co.jihun.guisample.scm;

/**
 * 저장소 파일 트리 항목의 종류 — 저장소 종류와 무관한 표현.
 *
 * <p>GitHub 기준 대응: {@code tree} → {@link #DIR}, {@code blob} → {@link #FILE}
 * (mode {@code 120000} 이면 {@link #SYMLINK}), {@code commit} → {@link #SUBMODULE}.
 */
public enum ScmEntryType
{
    /** 폴더. */
    DIR,
    /** 일반 파일. */
    FILE,
    /** 심볼릭 링크. */
    SYMLINK,
    /** 서브모듈(다른 저장소의 특정 커밋을 가리키는 항목). */
    SUBMODULE
}
