# QA 결과 — 저장소끌어오기 (2026-10-19)

## 2026-10-19 라운드 1 (frontend + backend 완료 통지)

검증 대상: backend(ScmClient.findTree, GitHubScmClient, RepoBrowseService, RepositoryRestController, MainController, 단위 테스트) / frontend(sidebar.jsp, user/repositories.jsp, common.css, e2e/specs/repositories.spec.ts). 기준은 설계 §12(점검완료는 배치만).

실행 결과: `./gradlew compileJava test --rerun` 성공 — 단위 테스트 33건(GitHubScmClientTest 11, RepoBrowseServiceTest 9, RepoPushServiceTest 5, RepoSyncServiceTest 8) 실패 0. 기존 가짜 ScmClient 2개는 `default findTree` 덕분에 수정 없이 컴파일된다.

### 통과 (경계면별)
- 1 REST ↔ JS: 컨트롤러 키 `success/page/total/records/rows/summary/message` ↔ repositories.jsp 의 `data.success/message/rows/summary` 일치. JS 가 읽는 행 필드 `path/name/parentPath/type/size/webUrl` 는 모두 `RepoFileRow` 에 있고, summary 필드 `repo/branch/commitId/commitMessage/commitDate/truncated/entryCount` 는 모두 `RepoTreeSummary` 에 있다. colModel 의 `rid` 는 클라이언트가 만드는 행 id 라 서버 필드가 아니어도 된다(의도). 실패 시 `rows: List.of()` → e2e `toEqual([])` 와 일치. type 문자열은 enum `name()` 이라 JS 비교값 `DIR/FILE/SYMLINK/SUBMODULE` 과 맞다.
- 4 라우팅: `MainController @GetMapping("/repositories")`(클래스 `/user`) → 사이드바 `href="/user/repositories"`, `activeMenu="repositories"` ↔ sidebar 비교값 `"repositories"` 일치. `@RequestMapping("/user/repository") + @GetMapping("/files")` ↔ JS `GET ${ctx}/user/repository/files`. PUBLIC_PATHS 변경 없음.
- 5 설정: yaml·build.gradle·e2e/package.json 변경 없음. 토큰은 기존 RestClient 기본 헤더를 그대로 쓴다. base URL 은 설정값으로 고정이라 사용자 입력으로 호출 대상 호스트를 바꿀 수 없다.
- 6 소유권: "해당 없음"의 근거가 코드와 맞다. 컨트롤러와 서비스 모두 매퍼·DB·LoginUserSession 을 쓰지 않고, 비동기 경로도 없다. 인증은 /user/** 가 강제한다.
- 7 e2e: 기존 id/class 는 바뀌지 않았다(sidebar 는 최상위 li#navRepositories 를 하나 추가했을 뿐이며 #navWorkflowSubmenu 의 4개 항목 그대로, projects.jsp 미수정). 다른 스펙에 `.nav-menu > li` 개수를 세는 검사는 없다. 신규 스펙이 쓰는 셀렉터는 모두 JSP 에 실제로 있다(#navRepositories, #searchRepoUrl, #searchBranch, #btnRepoPull, #btnInspectDone, #repoCommitBar/Branch/Sha, #repoBreadcrumb .repo-breadcrumb__item[data-path]/__current, #repoFileGrid, td[aria-describedby=repoFileGrid_name], .repo-file__dir/__file/__up, #appDialog/Message/Confirm). 스펙이 기대하는 "지원하지 않는" 문구는 ScmClientResolver 메시지 "지원하지 않는 저장소 URL 입니다" 와 일치한다. 사이드바 검사의 시작 화면인 dashboard.jsp 도 sidebar.jsp 를 include 한다.
- 규약: 파일명·경로·webUrl 은 escapeHtml 로 이스케이프하고, breadcrumb·커밋 바는 textContent 로 채운다. webUrl 은 http(s) 스킴만 링크로 만든다. appDialog.warn 만 쓰고 alert/confirm·var·인라인 style= 는 없다. dialog.jsp 는 정적 include, dialog.js 를 로드한다. sidebar 의 pageEncoding 이 유지되고 <jsp:include> 는 없다. CSS 는 repo-* 만 추가했고, 쓰는 변수(--warning, --font-mono 등)는 모두 정의돼 있다. pathSegment 원칙(branchUri 를 세그먼트로 쪼개 넣고 회귀 테스트 있음, webUrl 도 세그먼트 단위로 인코딩)을 지킨다. 점검완료 버튼에는 핸들러·disabled 가 없다(§12 준수).

### [MINOR] 경계면 1 — truncated 경고 문구의 건수 의미
- A: RepoBrowseService.browse — `entryCount = rows.rows().size()`(잘라낸 뒤 실제로 내려준 행 수)
- B: repositories.jsp renderCommitBar — `'(전체 ' + entryCount + '건 표시)'`
- 문구의 "전체"가 원래 항목 수처럼 읽히지만 값은 잘라낸 뒤의 행 수다. 제안: "(… 중 N건 표시)" 처럼 표시 건수임을 드러내는 문구로 바꾸거나, 원본 건수가 필요하면 summary 에 별도 필드를 둔다. 실제로는 2만 행 초과나 GitHub truncated 일 때만 보인다.

### [MINOR] 브랜치 세그먼트 `.`/`..` 미거부
- A: GitHubScmClient.branchUri — 빈 세그먼트만 거부한다
- `feature/../x` 같은 입력이 /repos/{o}/{r}/x 로 정규화될 여지가 있다. 호스트는 고정이고 GET 요청뿐이며 Git ref 규칙상 `..` 는 쓸 수 없는 이름이라 영향은 낮다. 제안: `.`·`..` 세그먼트도 "브랜치명이 올바르지 않습니다" 로 거부한다.

### 참고 (지적 아님)
- 설계와 다른 점: 행 id 를 순번 rid 로 둔 것, SUBMODULE 에 이동 링크를 만들지 않은 것, `repo-url-input` 클래스명, 브랜치를 지정하지 않았을 때의 404 를 빈 저장소 문구로 보내는 것. 모두 정합성에 문제없음을 확인했다.
- 2만 행 상한으로 잘릴 때 parentPath 순으로 자르므로, 일부 폴더는 행은 있는데 하위가 비어 보일 수 있다. 설계 A8 이 허용한 범위이며 경고 문구로 알린다.
- e2e·앱 서버는 실행하지 않았다(Phase 3 오케스트레이터 담당). `@github` 2건은 실제 GitHub 호출이 필요하다.

### 결론
BLOCKER 0 / MAJOR 0 / MINOR 2. 두 팀원 모두 검증을 마쳤다.

## 2026-10-19 라운드 2 (MINOR 수정 회귀 검증)

- [해결] truncated 경고 문구: repositories.jsp:364 를 "(표시 N건, 나머지는 생략)" 으로 고쳤다. N 은 entryCount 이다. 이 문구에 의존하는 e2e 셀렉터나 텍스트 검사는 없다.
- [해결] branchUri 의 `.`/`..` 세그먼트: GitHubScmClient.java:304 가 빈 세그먼트와 `.`·`..` 를 거부한다. GitHubScmClientTest 에 "../../x", "a/./b" 거부 단언이 추가됐다.
- `./gradlew compileJava test --rerun` 성공 — 33건, 실패 0.

### 결론
BLOCKER 0 / MAJOR 0 / MINOR 0 (미해결 없음). 남은 것은 e2e 실행(Phase 3)뿐이다.
