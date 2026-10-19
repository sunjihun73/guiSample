# 03 프론트 요약 — 저장소끌어오기 (2026-10-19)

## 화면 라우트·메뉴
| 라우트 | 뷰 | activeMenu |
|---|---|---|
| GET `/user/repositories` | `views/user/repositories.jsp` | `repositories` |

- `views/common/sidebar.jsp`: `.nav-menu` 의 **마지막 최상위** `li#navRepositories.nav-menu__item`(라벨·툴팁 "저장소끌어오기") 추가. 워크플로우 그룹 뒤에 두었고 `#navWorkflowSubmenu` 안에는 넣지 않았다. 상단 주석의 activeMenu 목록에 `repositories` 추가.
- 라우트(`MainController`)는 backend 담당.

## 호출 엔드포인트와 사용 필드
| 엔드포인트 | 사용 필드 |
|---|---|
| GET `/user/repository/files?repoUrl=&branch=` (jQuery ajax, `cache:false`) | `success`, `message`(실패), `rows[]`.`path`/`name`/`parentPath`/`type`/`size`/`webUrl`, `summary`.`repo`/`branch`/`commitId`/`commitMessage`/`commitDate`/`truncated`/`entryCount` |

- 미사용: `page`/`total`/`records`(그리드는 `datatype:"local"` 로 현재 폴더만 클라이언트 페이징), `rows[].depth`, `summary.repoUrl`/`owner`/`defaultBranch`.
- 필드명은 `dto/RepoFileRow`, `dto/RepoTreeSummary` record 컴포넌트와 대조 확인.

## 동작
- 끌어오기: URL 공백이면 `appDialog.warn` 후 중단(서버 호출 없음). 요청 중 `#btnRepoPull` disabled + 라벨 "끌어오는 중…". `success:false`/AJAX 오류 → 화면 초기화 + `appDialog.warn`(401/403 은 csrf.js 의 새로고침에 맡김).
- 성공: rows 를 `parentPath` 로 묶어 보관 → 루트 표시 → 커밋 바(브랜치·SHA 7자리·메시지 첫 줄·로컬 시각, truncated 면 경고) → 경로 표시.
- 폴더/`..`/경로 링크 클릭은 클라이언트에서만 처리(추가 서버 호출 없음). 파일은 `webUrl` 을 새 탭(`rel="noopener noreferrer"`).
- `#btnInspectDone` 은 배치만(핸들러·disabled·상태 표시 없음, 설계 §12).

## id/class
신규 id: `navRepositories`, `repoSearchForm`, `searchRepoUrl`, `searchBranch`, `btnRepoPull`, `btnRepoPullLabel`, `btnInspectDone`, `repoCommitBar`, `repoCommitBranch`, `repoCommitSha`, `repoCommitMessage`, `repoCommitDate`, `repoCommitWarn`, `repoBreadcrumb`, `repoFileGrid`, `repoFileGridPager`.

신규 class(common.css): `repo-url-input`, `repo-commit-bar`(`__branch`/`__sha`/`__message`/`__date`/`__warn`), `repo-breadcrumb`(`__item`/`__current`/`__sep`), `repo-file__dir`/`__file`/`__up`/`__plain`/`__icon`(`--dir`/`--file`). 기존 클래스 정의 변경 없음.

기존 id/class 변경 없음 → 기존 e2e 영향 없음(`workflow.spec.ts` 의 하위 메뉴 4개 유지).

## e2e
- 신규 `e2e/specs/repositories.spec.ts` (6개). `@github` 태그 2개는 실제 GitHub 호출. 점검완료 시나리오·초기 disabled 검사는 넣지 않음(§12). `package.json` 변경 없음.

## 설계와 다른 점
- 그리드 행 id 는 `path` 가 아니라 순번(`rid` = `r{i}`, `..` 는 `__up__`) — 경로의 특수문자가 DOM id 로 가지 않게. `path` 는 숨김 컬럼으로 유지.
- SUBMODULE 은 폴더 아이콘을 쓰되 이동 링크 없음(트리에 하위 항목이 없음).
- 입력 폭 보조 클래스는 `.search-form__input--wide` 대신 `repo-url-input`(지시: repo-* 만 추가).
- 사이드바 `li` 에 `id="navRepositories"` 추가(e2e 안정 셀렉터).
- 커밋 바·경로 표시는 끌어오기 전 `hidden` 속성으로 숨김.
