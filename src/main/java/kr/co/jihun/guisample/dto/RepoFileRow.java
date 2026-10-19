package kr.co.jihun.guisample.dto;

/**
 * 저장소끌어오기 그리드 행 ({@code GET /user/repository/files} 응답의 rows 원소).
 *
 * <p>화면은 전체 행을 받아 {@code parentPath} 로 묶어 두고 폴더 이동을 브라우저에서만 처리한다.
 *
 * @param path       저장소 루트 기준 경로 — 행 id
 * @param name       마지막 경로 세그먼트 (표시 이름)
 * @param parentPath 상위 폴더 경로 (루트 바로 아래면 빈 문자열 {@code ""})
 * @param type       {@code DIR} / {@code FILE} / {@code SYMLINK} / {@code SUBMODULE}
 * @param size       바이트 크기 — FILE 만, 그 외 null
 * @param depth      경로의 슬래시 개수 (루트 바로 아래 = 0)
 * @param webUrl     저장소 웹 화면 주소 (서브모듈 등은 null)
 */
public record RepoFileRow(String path, String name, String parentPath, String type,
                          Long size, int depth, String webUrl)
{
}
