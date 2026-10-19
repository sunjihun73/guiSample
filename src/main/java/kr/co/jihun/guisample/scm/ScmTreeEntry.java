package kr.co.jihun.guisample.scm;

/**
 * 파일 트리 항목 하나 ({@link ScmTree#entries()}).
 *
 * @param path   저장소 루트 기준 상대 경로 (슬래시 구분, 앞에 슬래시 없음)
 * @param type   항목 종류
 * @param size   바이트 크기 — 파일({@link ScmEntryType#FILE})만, 그 외는 null
 * @param webUrl 웹 화면에서 이 항목을 여는 주소 (없으면 null — 예: 서브모듈)
 */
public record ScmTreeEntry(String path, ScmEntryType type, Long size, String webUrl)
{
}
