# 설계: 저장소끌어오기 (GitHub 소스코드 목록 화면)

- 작성: feature-architect, 2026-10-19
- 관련 ADR: 0007 (REPO동기화·REPO PUSH — `ScmClient` 추상화, GitHub 은 `RestClient` 직접 호출)
- 상태: 초안 (사용자 확인 대상 표시: **[확인 필요]**)

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-19 | 최초 작성 |

---

## 0. 가정 목록 (모호한 요구에 대한 결정)

| # | 모호한 점 | 결정 | 근거 |
|---|---|---|---|
| A1 | "pull" 의 의미 | **서버 디스크에 clone/pull 하지 않는다.** GitHub REST API(Git Trees, `recursive=1`)로 해당 브랜치 최신 커밋의 **파일 트리만** 조회해 그리드에 보여 준다 | 완료판정이 "그리드에 목록이 잘 나타나면 성공"이다. clone 은 JGit 의존성·디스크·정리·동시 실행 경합을 끌고 오며 ADR 0007 결정 상세 1 이 같은 이유로 JGit 을 기각했다. API 방식은 끌어오기 1회당 **GitHub 호출 3회**로 끝난다(0.3 실측). 파일 내용이 필요해지면 그때 Contents/Blob API 를 추가한다 |
| A2 | "GitHub 처럼 보이는 목록" | **폴더 탐색형**: 루트에서 시작, 폴더 클릭 시 하위로, 맨 위 `..` 행과 경로 표시(breadcrumb)로 상위 이동. 폴더 먼저·이름순. 트리는 **끌어오기 때 한 번에 전부 받아 브라우저에 보관**하고 폴더 이동은 **클라이언트에서만** 처리(추가 GitHub 호출 0) | GitHub 의 코드 탭이 정확히 이 방식이다. 평탄 목록은 수백 행이 섞여 GitHub 와 닮지 않았고, jqGrid treeGrid 는 free-jqGrid 의 treeGrid 설정(adjacency, expand 상태, local 데이터 결합)이 무겁고 페이징과 함께 쓰기 어렵다 |
| A3 | 컬럼 구성 | 이름(아이콘+이름) / 종류(폴더·파일·서브모듈·링크) / 크기(파일만, KB 표기) / 경로(숨김, 행 id). 그리드 위에 **최신 커밋 바**(짧은 SHA·메시지·커밋 시각, 브랜치명)를 한 줄로 표시 | GitHub 의 "파일별 최근 커밋 메시지·날짜" 는 **파일마다 커밋 API 1회**가 필요하다(480개 저장소면 480회). 비인증 한도가 시간당 60회라 불가능 → 브랜치 단위 최신 커밋 1건만 보여 준다(비범위 참조) |
| A4 | 파일 클릭 | 새 탭으로 GitHub 의 해당 파일 페이지(`https://github.com/{o}/{r}/blob/{commitSha}/{path}`)를 연다. 앱 안의 내용 뷰어는 만들지 않는다 | 추가 API 호출 없이 GitHub 와 같은 사용감. 커밋 SHA 고정 링크라 이후 브랜치가 움직여도 화면과 같은 내용을 본다 |
| A5 | 브랜치명이 비었을 때 | 저장소의 **기본 브랜치**(`GET /repos/{o}/{r}` 의 `default_branch`)를 쓰고, 응답에 실제 사용한 브랜치명을 돌려준다. 화면은 브랜치 입력란이 비었으면 그 값으로 채우지 않고 최신 커밋 바에만 표시 | ADR 0007 의 기본 브랜치 관례와 같다. 입력란을 자동으로 채우면 "사용자가 입력한 것"과 구분이 안 된다 |
| A6 | "점검완료" 동작 **[확인 필요]** | **최소안(채택 제안)**: 끌어오기 성공 전에는 비활성. 클릭 → `appDialog.confirm`(저장소·브랜치·커밋 SHA·파일 수 요약) → 확인 시 화면에 "점검완료 (HH:mm, 커밋 abc1234)" 표시 + 버튼 비활성. **서버 호출·DB 저장 없음**, 새로고침하면 사라진다. 다시 끌어오기하면 점검 상태 초기화 | 요구사항에 정의가 없다. 저장이 필요한지(누가·언제·어느 커밋을 점검했는지 기록) 사용자 의도를 모르는 채 테이블을 만들면 되돌리기 비용이 크다. 저장안(B)은 9장에 DDL 초안까지 적어 두고, 확정되면 그 안으로 전환한다 |
| A7 | 페이징 | 응답은 jqGrid shape `{page, total, records, rows[]}` 를 **유지**하되 `page=1, total=1, records=전체 건수` 로 한 번에 내린다. 그리드는 `datatype: "local"` 로 현재 폴더의 자식만 표시, 클라이언트 페이징(rowNum 100, rowList [100, 300, 1000]) | 폴더 탐색을 클라이언트에서 하려면 전체 트리가 브라우저에 있어야 한다. 서버 페이징은 폴더 이동마다 GitHub 를 다시 불러 한도를 태운다. 응답 크기 상한은 서버에서 2만 행으로 자른다(A8) |
| A8 | 대형 저장소 | GitHub Trees API 자체가 10만 항목/7MB 를 넘으면 `truncated=true` 를 준다. 서버도 **20,000 행**에서 자르고 `truncated=true` 로 알린다. 화면은 경고 문구를 표시 | 대상 저장소(`sunjihun73/guiSample`)는 480 항목. 상한은 브라우저 메모리·응답 크기 방어용이다. 상수로 두고 설정화하지 않는다(설정 yaml 은 gitignore 대상이라 새 키가 늘수록 환경 누락 위험이 커진다 — ADR 0007 운영 주의) |
| A9 | 비공개 저장소 | 서버의 `GITHUB_TOKEN` 으로 호출하므로 **토큰이 읽을 수 있는 비공개 저장소의 파일 목록은 로그인한 누구나 볼 수 있다** **[확인 필요]** | 개인 학습/테스트 시스템에서 토큰은 소유자 본인 것이라 수용 가능하다고 본다. 막아야 하면 "공개 저장소만 허용"(저장소 메타의 `private=true` 면 거부) 한 줄로 막을 수 있다 |
| A10 | 메뉴명·제목 | 사이드바 라벨·페이지 제목 모두 "저장소끌어오기"(붙여 씀, 요구 원문 그대로) | 원문 |

### 0.3 실측 (2026-10-19, 토큰 없이)

| 호출 | 결과 |
|---|---|
| `GET /repos/sunjihun73/guiSample` | `private=false`, `default_branch=main` |
| `GET /repos/sunjihun73/guiSample/branches` | `dev`, `main` |
| `GET /repos/sunjihun73/guiSample/branches/dev` | commit `71de038…`, tree `c1c73df…`, 커밋 시각·메시지 포함 → **최신 커밋 바와 트리 SHA 를 한 번에 얻는다** |
| `GET /repos/sunjihun73/guiSample/git/trees/main?recursive=1` | `truncated=false`, 480 항목(blob 366, tree 114). 항목 필드: `path, mode, type(blob/tree/commit), sha, size(blob 만)` |
| `GET /repos/nodejs/node/branches/actions/inactive-collaborators` (슬래시 포함 브랜치) | 200. `%2F` 로 인코딩해도 200 → 브랜치명은 `pathSegment` 로 쪼개 넣어도, 하나로 넣어도 동작. **기존 관례대로 `pathSegment` 로 쪼갠다** |
| `GET /repos/sunjihun73/guiSample/branches/nope` | 404 |

---

## 1. 목표와 범위

### 시나리오
1. 사용자가 좌측 메뉴 맨 아래 **저장소끌어오기** 를 누른다 → `/user/repositories`.
2. 저장소URL `https://github.com/sunjihun73/guiSample.git`, 브랜치명 `dev`(또는 빈칸=기본 브랜치)를 넣고 **끌어오기**(회색) → 그리드에 루트의 폴더·파일이 GitHub 처럼 폴더 먼저·이름순으로 뜬다. 그리드 위에 `dev · 71de038 프로젝트 설명 2026-10-14 · 2026-10-14 15:52` 가 보인다.
3. `src` 폴더를 클릭 → 하위 목록, 경로 표시 `guiSample / src`, 맨 위 `..` 행으로 상위 이동. 파일 이름을 클릭하면 GitHub 파일 페이지가 새 탭으로 열린다. 다 보고 **점검완료**(파랑) → 확인 대화상자 → 점검완료 표시.

### 비범위
- 서버 디스크 clone/pull, 파일 내용 보기·다운로드, 파일별 최근 커밋 메시지·날짜(호출 수 한계 — A3).
- GitHub 이외 저장소(GitLab·SVN)의 실제 구현 — 인터페이스만 열어 둔다(`default` 메서드).
- 점검 결과 저장(A6 확정 전까지).

---

## 2. 데이터 모델

**해당 없음 — 앱 DB(5432)·워크플로우 DB(5433) 모두 스키마 변경 없음, DDL 없음, 매퍼 없음.**

근거: 화면에 보이는 목록은 GitHub 에서 매번 읽는 외부 데이터이고, 점검완료도 최소안(A6)은 저장하지 않는다. 저장안(B)으로 확정되면 9장의 DDL 을 앱 DB 에 적용한다.

신규 타입 (모두 DB 와 무관한 값 객체):

| 패키지 | 타입 | 내용 |
|---|---|---|
| `scm` | `ScmTree` (record) | `String branch`(실제 사용한 브랜치), `String defaultBranch`, `String commitId`, `String commitMessage`, `Instant commitDate`, `boolean truncated`, `List<ScmTreeEntry> entries` |
| `scm` | `ScmTreeEntry` (record) | `String path`(루트 기준, 슬래시 구분), `ScmEntryType type`, `Long size`(파일만, 그 외 null), `String webUrl`(nullable) |
| `scm` | `ScmEntryType` (enum) | `DIR, FILE, SYMLINK, SUBMODULE` — GitHub `tree`→DIR, `blob`→FILE(mode `120000` 이면 SYMLINK), `commit`→SUBMODULE |
| `dto` | `RepoFileRow` (record) | 그리드 행: `path`(id), `name`, `parentPath`(루트는 `""`), `type`(enum 이름 문자열), `size`, `depth`, `webUrl` |
| `dto` | `RepoTreeSummary` (record) | 응답 머리말: `repoUrl`, `owner`, `repo`, `branch`, `defaultBranch`, `commitId`, `commitMessage`, `commitDate`(ISO-8601 문자열 또는 Instant — Jackson 3 기본 직렬화 확인 필요), `truncated`, `entryCount` |

`owner`/`repo` 는 breadcrumb 루트 표시에 쓴다. 저장소 중립성을 위해 `ScmTree` 에는 넣지 않고, 서비스가 표시용으로 URL 에서 마지막 경로 이름만 꺼낸다(아래 4장 — `repo` 만 필요하면 `owner` 는 빼도 됨).

---

## 3. 소유권 처리

| 경로 | 유형 | 근거 |
|---|---|---|
| `GET /user/repository/files` (목록 끌어오기) | **해당 없음** | 앱 DB 의 어떤 행도 읽거나 쓰지 않는다. `user_master.user_name` 으로 거를 대상이 없다. 인증은 `/user/**` 이므로 `SecurityConfig` 가 강제(미인증 AJAX 는 401) |
| 점검완료 (최소안) | **해당 없음** | 서버 호출 없음 |
| 점검완료 (저장안 B, 확정 시) | **직접 컬럼** | `repo_inspection.inspect_user_id = user_master.user_name`. 컨트롤러가 `LoginUserSession.requireUserName()` 으로 요청 스레드에서 꺼내 서비스로 전달(9장) |

- 컨트롤러는 목록 경로에서 `LoginUserSession` 을 **쓰지 않는다**(쓸 조건이 없다). 감사 로그가 필요해지면 그때 `requireUserName()` 으로 꺼낸다.
- 비동기 경로(`boundedElastic`/SSE) 없음 — 동기 MVC 요청.
- 서버 토큰으로 비공개 저장소 목록이 보일 수 있는 점은 A9 **[확인 필요]**. 이것은 "남의 앱 데이터 노출"이 아니라 "서버 자격증명의 공유" 문제라 소유권 규칙의 예외로 분류하지 않는다.

---

## 4. 처리 흐름

```
[브라우저] /user/repositories — 끌어오기 버튼
   │  GET /user/repository/files?repoUrl=...&branch=...
   ▼
[앱] RepositoryRestController.files(repoUrl, branch)
   ▼
[앱] RepoBrowseService.browse(repoUrl, branch)
   ├─ repoUrl trim, 비었으면 IllegalArgumentException("저장소 URL 을 입력하세요")
   ├─ branch trim, 빈 문자열 → null (기본 브랜치)
   ├─ ScmClientResolver.resolve(repoUrl)   ── 지원하지 않는 URL 이면 ScmException (GitHub 호출 없음)
   ├─ ScmClient.findTree(repoUrl, branch)  ── GitHubScmClient
   │     1) GET /repos/{o}/{r}                         → default_branch (+ 404 = 저장소 없음)
   │     2) GET /repos/{o}/{r}/branches/{branch...}    → commit sha, message, committer.date, tree.sha
   │          (branch 는 pathSegment 로 쪼갬. 404 = "브랜치 '{b}' 를 찾을 수 없습니다")
   │     3) GET /repos/{o}/{r}/git/trees/{treeSha}?recursive=1  → 전체 항목, truncated
   │     → ScmTree (webUrl = https://{webHost}/{o}/{r}/{blob|tree}/{commitSha}/{path}, 서브모듈은 null)
   ├─ toRows(entries)  ── 순수 함수 (단위 테스트 대상)
   │     name = 마지막 세그먼트, parentPath = 마지막 '/' 앞(없으면 ""), depth = '/' 개수
   │     정렬: parentPath → (DIR·SUBMODULE 먼저) → name 대소문자 무시 → name
   │     20,000 행 초과 시 잘라내고 truncated=true
   └─ RepoTreeSummary + rows
   ▼
[브라우저] rows 전체를 JS 배열에 보관 → 현재 폴더(parentPath == cwd)만 jqGrid local 데이터로 표시
          폴더 클릭 / `..` / breadcrumb 클릭 → cwd 변경 후 다시 그림 (서버 호출 없음)
```

### `ScmClient` 확장 (ADR 0007 연장)

```java
/** 브랜치 최신 커밋의 전체 파일 트리. branch 가 null/빈 값이면 기본 브랜치. */
default ScmTree findTree(String repoUrl, String branch) {
    throw new ScmException("이 저장소는 파일 목록 조회를 지원하지 않습니다: " + repoUrl);
}
```

- `putFile` 과 같은 이유로 **`default`** — 기존·향후 조회 전용 구현체를 깨지 않는다. `RepoSyncService`·`RepoPushService` 는 손대지 않는다.
- GitHub 구현 세부:
  - 응답 record 는 기존처럼 `@JsonIgnoreProperties(ignoreUnknown = true)`. `default_branch` 는 `@JsonProperty("default_branch")`(`mapUnderscoreToCamelCase` 는 MyBatis 설정이라 Jackson 에는 적용되지 않음).
  - 1)에서 404 는 기존 `toScmException` 문구("저장소를 찾을 수 없음 (비공개 저장소면 토큰 필요)") 그대로. 2)의 404 만 브랜치 문구로 따로 바꾼다 — 1)을 먼저 부르는 이유가 이 구분이다(호출 1회를 더 쓰는 대신 오류가 명확).
  - 빈 저장소: 2)가 404 또는 409 → "커밋이 없는 빈 저장소이거나 브랜치가 없습니다".
  - URI 는 모두 `pathSegment`/URI 변수로 넣는다. base URL 은 설정(`scm.github.api-base-url`) 고정이므로 사용자 입력이 호출 대상 호스트를 바꿀 수 없다(SSRF 없음).
  - 타임아웃·리다이렉트·헤더·토큰은 기존 `RestClient` 인스턴스를 그대로 쓴다.
- **설정 변경 없음** — `application-local.yaml`/`application-e2e.yaml` 둘 다 손대지 않는다(기존 `scm.github.*` 재사용).
- **의존성 추가 없음.**

---

## 5. API 스펙

| Method | Path | 요청 | 응답 | 소유권 | 비고 |
|---|---|---|---|---|---|
| GET | `/user/repositories` | - | `user/repositories.jsp` | 해당 없음 | `MainController` 화면 라우트 |
| GET | `/user/repository/files` | query `repoUrl`(필수), `branch`(선택) | 아래 JSON | 해당 없음 | `RepositoryRestController`(신규, `@RequestMapping("/user/repository")`). 읽기 전용이라 GET(CSRF 토큰 불필요). `PUBLIC_PATHS` 대상 아님 |

응답 (성공·실패 모두 HTTP 200, `Map<String,Object>`):

```json
{
  "success": true,
  "page": 1, "total": 1, "records": 480,
  "rows": [
    { "path": "src", "name": "src", "parentPath": "", "type": "DIR",
      "size": null, "depth": 0, "webUrl": "https://github.com/sunjihun73/guiSample/tree/71de038.../src" },
    { "path": "build.gradle", "name": "build.gradle", "parentPath": "", "type": "FILE",
      "size": 2310, "depth": 0, "webUrl": "https://github.com/sunjihun73/guiSample/blob/71de038.../build.gradle" }
  ],
  "summary": {
    "repoUrl": "https://github.com/sunjihun73/guiSample.git", "owner": "sunjihun73", "repo": "guiSample",
    "branch": "dev", "defaultBranch": "main",
    "commitId": "71de038f8284d920449da7f14e4e6db2d2aac327",
    "commitMessage": "프로젝트 설명 2026-10-14", "commitDate": "2026-10-14T06:52:22Z",
    "truncated": false, "entryCount": 480
  }
}
```

실패: `{ "success": false, "message": "...", "page": 1, "total": 0, "records": 0, "rows": [] }`
- `IllegalArgumentException`(URL 비었음), `ScmException`(지원하지 않는 URL·저장소 없음·브랜치 없음·한도 초과·인증 실패) 모두 이 형태. 기존 `ProjectMasterRestController` 와 같이 `catch (Exception e)` 로 감싸 `message` 에 담는다.
- jqGrid shape 키는 실패여도 유지한다(화면이 실수로 그대로 그리드에 넣어도 깨지지 않게).

---

## 6. 화면 시나리오

### 라우트·메뉴
- `MainController`: `@GetMapping({"/repositories"})` → `"user/repositories"`.
- 뷰: `src/main/webapp/WEB-INF/views/user/repositories.jsp` (projects.jsp 의 head/topbar/app-shell/사이드바 토글 스크립트/dialog include 구조를 그대로 복제).
- `activeMenu = "repositories"`.
- `views/common/sidebar.jsp`: 워크플로우 그룹 `<li id="navWorkflowGroup">` **뒤**, `</ul class="nav-menu">` 직전에 최상위 `<li class="nav-menu__item<%= "repositories".equals(sidebarActive) ? " active" : "" %>">` 추가 → `<a href="/user/repositories">` + 아이콘 svg + `<span class="nav-label">저장소끌어오기</span>` + `<span class="tooltip">저장소끌어오기</span>`. 상단 주석의 `activeMenu 값` 목록에 `repositories` 추가. **워크플로우 하위 메뉴(`#navWorkflowSubmenu`) 안에 넣지 말 것** — `workflow.spec.ts` 가 하위 항목 4개를 센다.

### 화면 구성 (id/class — 모두 신규)

| 요소 | id / class | 비고 |
|---|---|---|
| 페이지 헤더 | `.page-header` (eyebrow `Repositories`, title `저장소끌어오기`) | |
| 조회 폼 | `form#repoSearchForm.search-form` (`onsubmit="return false;"`) | |
| 저장소URL | `label[for=searchRepoUrl]` "저장소URL", `input#searchRepoUrl.search-form__input` placeholder `https://github.com/owner/repo.git` | Enter → 끌어오기 |
| 브랜치명 | `label[for=searchBranch]` "브랜치명", `input#searchBranch.search-form__input` placeholder `비우면 기본 브랜치` | Enter → 끌어오기 |
| 끌어오기 | `button#btnRepoPull.search-form__btn.search-form__btn--gray` | 요청 중 `disabled` + 문구 "끌어오는 중…" |
| 점검완료 | `button#btnInspectDone.search-form__btn` (기본 = primary 파랑) | 초기 `disabled`. 끌어오기 성공 시 활성 |
| 점검 상태 | `span#repoInspectStatus` | 점검완료 후 "점검완료 · HH:mm · abc1234" |
| 최신 커밋 바 | `div#repoCommitBar.repo-commit-bar` | 브랜치, 짧은 SHA(7), 메시지 첫 줄, 커밋 시각(로컬 시간). `truncated` 면 경고 문구 `.repo-commit-bar__warn` |
| 경로 표시 | `nav#repoBreadcrumb.repo-breadcrumb` | `repo` 이름(루트) / 세그먼트 각각 클릭 가능(`a[data-path]`) |
| 그리드 | `table#repoFileGrid`, `div#repoFileGridPager` (`.grid-wrap` 안) | caption "소스코드" |

### jqGrid
- `datatype: "local"`, `data: []` 로 초기화. 끌어오기 전에는 `emptyrecords: "저장소URL 을 입력하고 끌어오기를 누르세요."`.
- colModel: `path`(key, hidden) / `name` "이름"(formatter: DIR·SUBMODULE 은 폴더 아이콘 + `<a href="#" class="repo-file__dir" data-path>`; FILE·SYMLINK 는 파일 아이콘 + `<a class="repo-file__file" href="{webUrl}" target="_blank" rel="noopener">`; `..` 행은 `repo-file__up`) / `type` "종류"(폴더·파일·링크·서브모듈 한글 변환, width 90, center) / `size` "크기"(FILE 만 `1.2 KB` 형식, right). `sortable: false`(서버 정렬 유지 — GitHub 처럼 폴더 먼저).
- **이름은 반드시 이스케이프**해서 formatter 에 넣는다(파일명은 저장소 작성자가 정한 외부 문자열 — `<`·`"` 가 들어올 수 있음). `data-path` 속성값도 이스케이프.
- 클라이언트 페이징: `rowNum: 100, rowList: [100, 300, 1000]`, `viewrecords: true`, `rownumbers: true`. `..` 행은 현재 폴더 데이터 맨 앞에 넣는 가상 행(`path: "__up__"`).
- 폴더 이동: `cwd` 변경 → `clearGridData` → `setGridParam({ data: childrenOf(cwd), page: 1 })` → `trigger("reloadGrid")` → breadcrumb 갱신. 자식 목록은 끌어오기 직후 `parentPath` 로 한 번 묶어 둔다(`Map<parentPath, rows[]>`).

### 동작
- 끌어오기: URL 비었으면 `appDialog.warn("저장소URL 을 입력하세요.")` 후 중단(서버 호출 없음). `$.ajax GET` → `success:false` 면 `appDialog.warn(message)`, 그리드는 비움, 점검완료 비활성. 성공이면 rows 보관 → `cwd=""` → 그리기 → 커밋 바 → 점검 상태 초기화 → 점검완료 활성. AJAX 오류(401/403 은 `csrf.js` 가 새로고침)는 `appDialog.warn`.
- 점검완료(A6 최소안): `appDialog.confirm("다음 저장소를 점검완료 처리합니다.\n{repo} · {branch} · {sha7}\n파일 {n}개", { onConfirm: ... })` → `#repoInspectStatus` 표시, 버튼 `disabled`, `appDialog.alert("점검완료 처리했습니다.")`. **`if (confirm(...))` 식 금지** — 결과는 `onConfirm` 콜백으로.
- `dialog.jsp` 정적 include, `dialog.js` 로드. 기본 `alert()`/`confirm()` 사용 금지(신규 스펙이 `afterEach` 로 감시).
- JS 변수는 `let`. 인라인 스타일 금지 → `common.css` 에 `.repo-commit-bar`, `.repo-commit-bar__warn`, `.repo-breadcrumb`, `.repo-file__dir`, `.repo-file__file`, `.repo-file__up`, `.repo-file__icon`, 그리고 두 입력 필드가 한 줄에 놓이도록 필요한 경우 `.search-form` 보조 클래스(예: `.search-form__input--wide`)만 추가. 기존 클래스 정의는 바꾸지 않는다.
- 페이지 스크립트는 JSP 인라인으로 둔다(projects.jsp 관례). 별도 `static/js/repositories.js` 는 만들지 않아도 된다(선택).

### e2e 영향
- **기존 스펙 셀렉터 영향 없음.** projects.jsp 는 수정하지 않는다. sidebar.jsp 는 최상위 `li` 하나 추가뿐이며, `workflow.spec.ts` 가 보는 `#navWorkflowToggle`, `#navWorkflowSubmenu .nav-submenu__item`(4개)은 그대로다.
- **신규 스펙 `e2e/specs/repositories.spec.ts`** 필요 (8장 frontend 담당).

---

## 7. 검증 계획

### 단위 테스트 (`./gradlew test`, 외부 의존 없음)
- `src/test/.../service/RepoBrowseServiceTest.java`
  - `toRows`: `parentPath`/`name`/`depth` 계산(`a`, `a/b`, `a/b/c.txt`), 정렬(같은 폴더에서 DIR 먼저·대소문자 무시 이름순), 20,000 초과 시 잘림 + `truncated`.
  - 가짜 `ScmClient`(기존 `RepoSyncServiceTest` 방식)로 `browse`: URL 공백 → 예외, 브랜치 빈 문자열 → `findTree(..., null)` 호출, 결과 summary 매핑.
- `src/test/.../scm/github/GitHubScmClientTest.java` 에 추가
  - 트리 항목 → `ScmTreeEntry` 변환 순수 함수(`tree`→DIR, `blob`→FILE, mode `120000`→SYMLINK, `commit`→SUBMODULE, size 는 FILE 만).
  - `webUrl` 생성(blob/tree 구분, 경로 슬래시 유지, 서브모듈 null).
  - 슬래시 포함 브랜치(`feature/x`)의 branches URI 가 `/repos/acme/app/branches/feature/x` 로 만들어지는지(기존 `contentsUri` 테스트와 같은 `DefaultUriBuilderFactory` TEMPLATE_AND_VALUES 방식).
  - 변환·URI 함수는 테스트 가능하도록 package-private static 으로 둔다(기존 `parse`/`contentsUri` 관례).

### e2e (`e2e/specs/repositories.spec.ts`)
1. 사이드바 마지막 최상위 항목이 "저장소끌어오기"이고 클릭 시 `/user/repositories`, 해당 `li` 에 `active`.
2. 화면 요소: 제목, `#searchRepoUrl`, `#searchBranch`, `#btnRepoPull`(`search-form__btn--gray`), `#btnInspectDone`(초기 disabled), `#repoFileGrid`.
3. URL 비우고 끌어오기 → `#appDialog` 경고(네트워크 호출 없음).
4. 지원하지 않는 URL(`https://gitlab.com/a/b`) → `success:false` 경고(GitHub 호출 없음 — resolver 에서 거부).
5. **완료판정 시나리오 `@github` 태그**: `https://github.com/sunjihun73/guiSample.git` + `main` → 그리드에 `src`, `build.gradle` 행, 커밋 바 표시, `src` 클릭 → `main` 폴더 보임, `..` 로 복귀, 점검완료 → `#appDialog` 확인 버튼 클릭 → `#repoInspectStatus` 표시·버튼 disabled. 브랜치 빈칸 → 커밋 바에 `main`.
   - 실제 GitHub 를 1회당 3번 호출한다(비인증 60회/시간). 기본 `npm test` 에 포함하되 `@github` 태그를 달아 오프라인·한도 소진 시 `--grep-invert "@guardrail|@github"` 로 뺄 수 있게 한다. `package.json` 스크립트는 바꾸지 않는다(완료판정이 이 시나리오이므로 기본 포함). 
6. `afterEach` 로 기본 대화상자 감시(`workflow.spec.ts` 관례).

- 회귀: `workflow.spec.ts`(사이드바), `projects.spec.ts` 전체 재실행.

### 매퍼
해당 없음(매퍼 변경 없음).

### 수동 확인 (QA)
- `dev` 브랜치 끌어오기 → 커밋 `71de038` 표시 확인, 존재하지 않는 브랜치 `nope` → "브랜치 'nope' 를 찾을 수 없습니다".
- 응답 `rows` 수 = GitHub Trees API 항목 수(480 근처, 커밋 시점에 따라 변동).

---

## 8. 분배 지시

### mybatis-data-engineer
**해당 없음** — DDL·매퍼 변경 없음. (A6 저장안 B 확정 시 9장 DDL·매퍼 담당)

### spring-backend-engineer
1. `scm/ScmEntryType.java`, `scm/ScmTree.java`, `scm/ScmTreeEntry.java` 신규 (record/enum, javadoc 에 저장소 중립 의미).
2. `scm/ScmClient.java`: `default ScmTree findTree(String repoUrl, String branch)` 추가(기본 구현은 `ScmException`). 기존 메서드 불변.
3. `scm/github/GitHubScmClient.java`: `findTree` 구현(4장 3단계 호출, 응답 record 추가, 404 문구 구분, `pathSegment`, `webUrl`). 변환·URI 함수는 package-private static.
4. `dto/RepoFileRow.java`, `dto/RepoTreeSummary.java` 신규.
5. `service/RepoBrowseService.java` 신규: `browse(String repoUrl, String branch)` → (summary, rows) 반환 타입은 내부 record 또는 `RepoTreeSummary`+`List<RepoFileRow>` 묶음. `toRows` 는 static 순수 함수. `@Transactional` 없음(DB 미사용).
6. `controller/user/RepositoryRestController.java` 신규: `GET /user/repository/files` — 5장 응답 shape. 예외는 모두 `success:false` + jqGrid 빈 shape.
7. `controller/user/MainController.java`: `/repositories` 라우트.
8. 단위 테스트(7장). `RepoSyncServiceTest`·`RepoPushServiceTest` 의 가짜 `ScmClient` 는 `default` 덕분에 수정 불필요 — 컴파일 확인.
9. 설정·의존성 변경 없음(두 프로파일 yaml 모두 그대로).

### jsp-frontend-engineer
1. `views/common/sidebar.jsp`: 최상위 메뉴 항목 추가(6장), 주석의 activeMenu 목록 갱신. `pageEncoding` 지시자 유지.
2. `views/user/repositories.jsp` 신규(6장 id/class 그대로).
3. `static/css/common.css`: 6장의 신규 클래스만 추가.
4. `e2e/specs/repositories.spec.ts` 신규(7장). 상단 주석에 의존 셀렉터 목록.

### 문서 (오케스트레이터/마지막 단계)
- ADR 0007 에 "결정 상세 9. 저장소끌어오기 — `findTree` (2026-10-19)" 와 구성 요소·변경 이력 행 추가(**결정 변경이 아니라 같은 추상화의 연장**).
- `CLAUDE.md` 의 "REPO동기화 / REPO PUSH" 섹션에 저장소끌어오기 한 단락, e2e 스펙 목록에 `repositories`, 변경 이력 행.
- `README.md` 화면·REST API 표.

---

## 9. 점검완료 저장안 (B) — **[확인 필요]**, 확정 시에만 적용

사용자가 "점검 기록을 남겨야 한다"고 하면 아래로 전환한다.

- DDL `src/main/resources/db/repo_inspection_schema.sql` — **앱 DB(5432)에 수동 적용, 코드보다 먼저.**
  ```sql
  CREATE TABLE IF NOT EXISTS repo_inspection (
      inspection_id    VARCHAR(36)  PRIMARY KEY,
      repo_url         VARCHAR(500) NOT NULL,
      branch_name      VARCHAR(255) NOT NULL,
      commit_id        VARCHAR(64)  NOT NULL,
      entry_count      INTEGER      NOT NULL,
      inspect_user_id  VARCHAR(100) NOT NULL,   -- user_master.user_name (소유권 키)
      inspect_date     TIMESTAMP    NOT NULL DEFAULT now()
  );
  CREATE INDEX IF NOT EXISTS idx_repo_inspection_user ON repo_inspection (inspect_user_id, inspect_date DESC);
  ```
- API: `POST /user/repository/inspections` (본문 `repoUrl, branch, commitId, entryCount`) → `{success, inspectionId}`. 소유권 **직접 컬럼** — `inspect_user_id` 는 본문이 아니라 컨트롤러가 `LoginUserSession.requireUserName()` 으로 넣는다. 조회 화면이 생기면 `inspect_user_id` 조건을 count 전에 param 에 넣는다.
- 서버가 `commitId` 를 신뢰할지(재조회 검증 여부)는 그때 결정.
- 미적용 시: INSERT 실패 → `success:false` 경고로 드러난다(조용히 넘어가지 않음).

---

## 10. ADR 변경 필요 여부

**ADR 결정 변경 필요 없음.**

- ADR 0007 의 결정(`ScmClient` 추상화, `RestClient` 직접 호출, hub4j·JGit 미채택)을 그대로 따른다. 필요한 API 는 저장소 메타·브랜치·트리 3개뿐이라 라이브러리 도입 근거가 더 약해졌다.
  - JGit: 요구가 "목록 표시"라 clone 이 과하다(A1). Boot 4 충돌은 없지만 디스크·정리·동시성 부담과 대형 저장소 시간 비용이 크다.
  - hub4j: ADR 0007 의 기각 사유(Jackson 2 공존, Boot 4 미검증)가 그대로 유효.
- 추가 기록만 필요: ADR 0007 에 결정 상세 9(`findTree` default 메서드, 3회 호출, 폴더 탐색은 클라이언트, 소유권 해당 없음, 서버 토큰 공유 A9)를 덧붙인다.

## 11. 사용자 확인이 필요한 결정 (오케스트레이터 전달용)

1. **점검완료 동작(A6)**: 최소안(화면 표시만, 저장 없음)으로 갈지, 저장안 B(`repo_inspection` 테이블, 앱 DB)로 갈지.
2. **비공개 저장소(A9)**: 서버 토큰으로 읽히는 비공개 저장소 목록을 모든 로그인 사용자에게 허용할지, 공개 저장소만 허용할지.
3. (참고) 파일별 최근 커밋 메시지·날짜 컬럼은 호출 수 한도 때문에 넣지 않았다(A3). 꼭 필요하면 토큰 필수 + 현재 폴더 자식에 한해 지연 조회하는 2차 작업으로 분리.

---

## 12. 사용자 결정 (2026-10-19, 오케스트레이터 기록) — **이 장이 위 내용보다 우선한다**

1. **점검완료: 이번에는 구현하지 않는다.**
   - `button#btnInspectDone.search-form__btn`(파랑)은 요구사항 4-3 대로 화면에 **배치만** 한다. 클릭 핸들러·`appDialog.confirm`·`disabled` 토글·`#repoInspectStatus` 를 만들지 않는다(버튼은 기본 상태로 둠).
   - 9장 저장안 B 는 적용하지 않는다. DDL·매퍼 없음.
   - e2e 는 `#btnInspectDone` 존재와 `search-form__btn--gray` 가 아님(파랑)만 확인한다. 점검완료 시나리오(7장 5번 후반)는 빼고, "초기 disabled" 검사도 뺀다.
2. **비공개 저장소: 그대로 허용.** 서버 `GITHUB_TOKEN` 이 읽을 수 있으면 조회한다(공개 여부 검사 없음).
3. 파일별 최근 커밋 컬럼은 넣지 않는다(설계대로).
