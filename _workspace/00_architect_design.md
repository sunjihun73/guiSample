# 설계: 저장소끌어오기 (GitHub 소스코드 목록 화면)

- 작성: feature-architect, 2026-10-19
- 관련 ADR: 0007 (REPO동기화·REPO PUSH — `ScmClient` 추상화, GitHub 은 `RestClient` 직접 호출)
- 상태: 초안 (사용자 확인 대상 표시: **[확인 필요]**)
- 후속 1 (2026-10-19): 끌어오기 결과를 브라우저 PC 폴더에 저장 — **§13 이 이 기능의 현재 설계**다. §13 과 앞 장이 다르면 §13 이 우선한다.

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-19 | 최초 작성 |
| 2026-10-19 (후속 1) | **끌어오기 → 브라우저 PC 폴더 저장** 추가 — §13 신설. A1·§1 비범위의 "파일 내용 다운로드 제외"는 §13 이 대체한다(나머지 §0~§12 는 유효). 점검완료는 계속 미구현(§12 유지) |

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

---

## 13. 후속 1 — 끌어오기 결과를 브라우저 PC 폴더에 저장 (2026-10-19)

> 이 장이 §0~§12 보다 우선한다. 기존 화면(트리 조회 → 폴더 탐색 그리드)과 `GET /user/repository/files` 는 **그대로 두고**, 같은 버튼 흐름에 "폴더 선택 → 파일 저장"을 덧붙인다. 점검완료는 계속 배치만(§12).

### 13.0 사용자 요구·확정 결정

| # | 요구 / 결정 | 출처 |
|---|---|---|
| R1 | `끌어오기` 클릭 → 저장할 디렉토리 선택창 | 요구 1 |
| R2 | 선택 폴더 아래 `{저장소명}/` 를 만들고 그 밑에 파일 저장 | 요구 2·3 |
| R3 | 같은 이름 파일은 덮어쓴다 | 요구 4 |
| R4 | 점검완료는 여전히 미구현 | 요구 5 |
| D1 | 저장 위치는 **브라우저 PC 폴더**. File System Access API `window.showDirectoryPicker({mode:'readwrite'})`(Chrome/Edge). **서버 디스크에는 쓰지 않는다** | 사용자 확정 |
| 완료판정 | `https://github.com/sunjihun73/guiSample.git` 의 지정 브랜치를 지정 폴더에 실제 파일로 끌어온다 | 요구 |

### 13.1 가정 (이번 후속분)

| # | 모호한 점 | 결정 | 근거 |
|---|---|---|---|
| B1 | 폴더 선택을 **취소**하면 | **목록 조회만** 하고 저장하지 않는다. 대화상자는 띄우지 않고 상태 문구 `#repoSaveStatus` 에 "저장 안 함 (폴더 선택 취소)" | 기존 목록 기능을 저장과 무관하게 쓸 수 있게 둔다. 취소는 사용자가 의도한 행동이라 경고가 필요 없다 |
| B2 | API 미지원(Firefox·Safari, **비보안 주소 http**) | 목록 조회는 진행하고, 목록이 뜬 뒤 `appDialog.warn` 으로 "폴더 저장을 쓸 수 없어 목록만 표시했다 + 사유" 안내. 사유는 `window.isSecureContext` 로 나눈다(아래 13.6) | 요구가 "선택창을 띄운다"이므로 못 띄우면 알려야 한다. 목록까지 막으면 기존 기능 회귀 |
| B3 | 기존 파일 중 저장소에 **없는** 파일 | **지우지 않는다.** 덮어쓰기만 한다(동기화·미러 아님) | 요구 4 는 덮어쓰기만 말한다. 사용자 PC 파일 삭제는 되돌릴 수 없어 명시 요구 없이 하지 않는다 |
| B4 | 저장소명 | 응답 `summary.repo`(서버가 URL 에서 꺼낸 이름, `.git` 제거 — `RepoBrowseService.ownerAndRepo`). 브라우저가 한 번 더 안전 세그먼트 검사 | 요구 3. 서버 값을 재사용해 규칙이 한 곳 |
| B5 | 저장 내용의 커밋 | 트리 조회 응답의 `summary.commitId`(40자 SHA)로 아카이브를 받는다 → **그리드와 저장 파일이 같은 커밋** | 트리 조회와 다운로드 사이에 브랜치가 움직여도 어긋나지 않는다 |
| B6 | 심볼릭 링크 | **링크 대상 경로를 내용으로 하는 일반 텍스트 파일**로 저장(git `core.symlinks=false` 와 같은 동작). 실제 링크는 만들지 않는다 | FSA 로는 링크를 만들 수 없고, 링크를 따라가지 않으므로 경로 이탈이 원천 차단된다. `java.util.zip.ZipInputStream` 은 외부 속성(링크 여부)을 노출하지 않아 서버가 구분할 방법도 없다(구분하려면 zip 중앙 디렉터리를 직접 파싱해야 함 — 과함) |
| B7 | 서브모듈 | **저장하지 않는다.** GitHub zipball 에 서브모듈 내용이 들어 있지 않다(빈 폴더 항목 → 디렉터리 항목이라 건너뜀) | 다른 저장소다. 목록에서도 이동 링크가 없다(§03 프론트 요약) |
| B8 | 빈 폴더 | 만들지 않는다(파일이 있는 폴더만 생김) | git 은 빈 폴더를 추적하지 않는다. zip 의 디렉터리 항목은 건너뛴다 |
| B9 | 선택 폴더 표시 | 브라우저는 **전체 경로를 알려주지 않는다**. `dirHandle.name`(선택 폴더 이름)만 표시: "`{폴더이름}/{repo}`" | FSA 보안 모델 |

### 13.2 실측 (2026-10-19, 토큰 없이)

| 확인 | 결과 |
|---|---|
| `GET https://api.github.com/repos/sunjihun73/guiSample/zipball/main` | **302** → `https://codeload.github.com/sunjihun73/guiSample/legacy.zip/refs/heads/main`. 응답 후 `x-ratelimit-remaining: 60`(이 호출이 한도를 깎지 않은 것으로 보이나 **확인 필요** — 설계는 1회 소모로 계산) |
| zip 크기·구성 | 압축 **2.0 MB**, 풀면 **7.7 MB**, 항목 496개 = 파일 380 + 디렉터리 116. 최상위 폴더 `sunjihun73-guiSample-8a63fb2/` 하나 아래에 전부 있다. zip 주석에 커밋 SHA 전체가 들어 있다 |
| Java 21 `ZipInputStream` 로 끝까지 읽기 | 예외 없음. STORED 2·DEFLATED 378. 로컬 헤더 496개 중 **data descriptor(flag bit 3) 0개** — `ZipInputStream` 의 "STORED + data descriptor 불가" 제약에 걸리지 않는다 |
| Spring 7.0.7 `JdkClientHttpRequestFactory.setReadTimeout` | 소스 확인: `TimeoutHandler` 가 **응답 본문까지 포함한 총 시간**이 지나면 본문 스트림을 닫는다(헤더 수신 시간만이 아님). → 현재 `RestClient`(20초)로 아카이브를 받으면 큰 저장소에서 잘린다. **아카이브 전용 RestClient(별도 타임아웃)** 가 필요하다 |
| `showDirectoryPicker` (MDN) | **보안 컨텍스트(HTTPS 또는 localhost) 전용**, **일시적 사용자 활성화(transient activation) 필수** — 없으면 `SecurityError`. 취소·민감 폴더·권한 미부여는 `AbortError`. 옵션 `id`(마지막 폴더 기억), `mode`, `startIn` |
| `createWritable()` (MDN) | `keepExistingData` 기본 `false` → 빈 임시(swap) 파일에 쓰고 **`close()` 때 원본을 교체** = 덮어쓰기(R3) |
| **앱 접속 주소** | `e2e/.env.e2e` `APP_URL=http://mytechtest.jihun.com`, nginx `:80` 만 있음(README). **http 라 보안 컨텍스트가 아니다 → 이 주소에서는 `window.showDirectoryPicker` 가 아예 정의되지 않는다.** 13.10 의 사용자 확인 1 참조 |

### 13.3 결정 — 파일 내용 획득 방식

| 후보 | GitHub 호출 | 판단 |
|---|---|---|
| Contents/Blob API 파일마다 | 파일 수만큼(380회) | **기각** — 비인증 60회/시간 한도로 불가능 |
| 서버가 zip 을 그대로 프록시 → 브라우저가 zip 해제(`DecompressionStream('deflate-raw')` + 중앙 디렉터리 파싱) | 1 | **기각** — 브라우저에 zip 파서(EOCD·중앙 디렉터리·ZIP64·플래그·UTF-8 이름)를 직접 짜야 하고, JS 는 단위 테스트 기반이 없다(e2e 뿐). 경로 검사가 브라우저에만 남는다 |
| 서버가 zip 을 풀어 JSON(base64)로 | 1 | 기각 — 1.33배 팽창 + JSON 전체를 메모리에 올려야 해 스트리밍이 안 된다 |
| **서버가 zipball 을 받아 `java.util.zip` 으로 풀고, 브라우저가 읽기 쉬운 단순 프레임 스트림으로 내려준다** (채택) | **1** (+ codeload 리다이렉트) | 외부 라이브러리 0(서버·브라우저 모두). zip 처리·경로 검사·상한을 **서버 Java 에서 단위 테스트**할 수 있다. 브라우저는 길이 접두 프레임만 읽으면 된다(약 60줄). 파일 단위로 흘려보내 브라우저 메모리가 작다 |

**zip 을 먼저 메모리에 다 받고 나서 푸는 이유** (스트림 직결 아님): 브라우저가 파일을 쓰는 속도가 GitHub 다운로드를 끌고 다니면 13.2 의 총 타임아웃·연결 점유 시간이 브라우저 속도에 묶인다. 압축본을 상한(50 MiB) 안에서 `byte[]` 로 받은 뒤 GitHub 연결을 닫고 푼다. 서버 디스크(임시 파일)는 쓰지 않는다(D1).

### 13.4 처리 흐름

```
[브라우저] #btnRepoPull 클릭 (또는 입력란 Enter)
  ① URL 비었음 → appDialog.warn, 끝 (기존과 동일, picker 호출 안 함)
  ② 버튼 disabled 중이면 무시 (기존과 동일)
  ③ ★ picker 를 '동기적으로' 먼저 호출 — await·ajax·appDialog 보다 앞 ★
       pickerPromise = (typeof window.showDirectoryPicker === 'function')
           ? window.showDirectoryPicker({ id: 'guisample-repo-pull', mode: 'readwrite', startIn: 'documents' })
           : null
       (일시적 사용자 활성화는 클릭 직후 짧게만 유효(Chrome 약 5초). 트리 조회 뒤에 부르면 SecurityError)
  ④ 버튼 라벨 "폴더 선택 중…" → dirHandle = await pickerPromise
       AbortError → dirHandle=null, saveMode='cancelled'
       그 밖의 오류(SecurityError 등) → dirHandle=null, saveMode='error'(메시지 보관)
       pickerPromise==null → saveMode='unsupported'
  ⑤ 라벨 "끌어오는 중…" → GET /user/repository/files (기존 그대로)
       실패 → resetView + 기존 경고, 끝 (저장 안 함)
       성공 → 기존대로 그리드·커밋 바 표시
  ⑥ saveMode 별
       'cancelled'   → #repoSaveStatus "저장 안 함 (폴더 선택 취소)", 대화상자 없음
       'unsupported' → appDialog.warn(13.6 문구)
       'error'       → appDialog.warn("폴더를 열지 못해 목록만 표시했습니다.\n" + 오류)
       dirHandle 있음 → ⑦
  ⑦ 저장: repoDir = await dirHandle.getDirectoryHandle(summary.repo, {create:true})
       fetch GET /user/repository/archive?repoUrl=…&ref={summary.commitId}
         헤더 X-Requested-With: XMLHttpRequest (만료 시 302 대신 401 → location.reload())
       프레임을 읽으며 파일마다: 경로 재검사 → 하위 폴더 핸들(Map 캐시) → getFileHandle(name,{create:true})
         → createWritable() → write(bytes) → close()   (= 덮어쓰기)
       진행: 라벨 "저장 중… n/N" (N = 트리 rows 중 FILE+SYMLINK 수, 같은 커밋이라 기대값으로 쓸 수 있음)
  ⑧ 결과: 'Z' 수신 → appDialog.alert("'{폴더}/{repo}' 에 파일 {n}개를 저장했습니다." [+ 건너뜀 k건][+ 실패 m건: 앞 5개 경로])
       'E' 수신 / 스트림이 'Z' 없이 끝남 / HTTP 오류 → appDialog.warn("저장 중 오류 … {n}개까지 저장됨\n{메시지}")
       #repoSaveStatus 에 "저장: {폴더}/{repo} · 파일 n개 · HH:mm" (실패면 "저장 실패")
  ⑨ always: 버튼 활성·라벨 "끌어오기"

[앱] GET /user/repository/archive → RepositoryRestController.archive → RepoArchiveService.streamArchive
   ├─ repoUrl 공백 → 오류 프레임 "저장소 URL 을 입력하세요"
   ├─ ref 공백 → 오류 프레임 "커밋 ID 가 없습니다. 먼저 목록을 끌어오세요"
   ├─ ScmClientResolver.resolve(url)   (지원 안 하는 URL → 오류 프레임, GitHub 호출 없음)
   └─ ScmClient.readArchive(url, ref, visitor) ── GitHubScmClient
        1) GET /repos/{o}/{r}/zipball/{ref 세그먼트…}   (아카이브 전용 RestClient, 302 → codeload 자동 추적)
             Content-Length > 50 MiB 면 즉시 거부, 없으면 읽으면서 50 MiB 초과 시 거부 → byte[]
             404 → "저장소 또는 커밋을 찾을 수 없음 …"(기존 toScmException), 403/429 → 한도 문구
        2) GitHub 연결을 닫은 뒤 ZipInputStream 으로 순회
             디렉터리 항목 → 건너뜀(보고 안 함)
             최상위 폴더 제거: 첫 항목의 첫 세그먼트를 root 로 고정, 다른 root 로 시작하는 항목 → onSkipped
             ScmPaths.safeRelativePath(rel) 가 null → onSkipped(path, 사유)
             통과 → visitor.onFile(rel, entryStream)
   visitor(서비스): 파일 수 ≤ 20,000, 파일당 ≤ 100 MiB, 합계 ≤ 500 MiB (넘으면 ScmException)
                    → RepoArchiveFrameWriter.writeFile(rel, bytes) / writeSkipped(...)
   끝 → writeEnd(fileCount, skippedCount, totalBytes).  예외 → writeError(message). 밖으로 던지지 않는다
```

### 13.5 API 스펙 (추가분)

| Method | Path | 요청 | 응답 | 소유권 | 비고 |
|---|---|---|---|---|---|
| GET | `/user/repository/archive` | query `repoUrl`(필수, `required=false` 로 받고 비면 오류 프레임), `ref`(필수 — 화면은 `summary.commitId` 를 보낸다. 커밋 SHA·브랜치명 모두 허용) | **항상 HTTP 200** `Content-Type: application/octet-stream` 프레임 스트림(아래). `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. `Content-Disposition` 없음(fetch 가 소비) | 해당 없음 | 읽기 전용이라 GET(CSRF 불필요). `PUBLIC_PATHS` 아님 → 미인증 AJAX 는 401. `GET /user/repository/files` 는 **변경 없음** |

**프레임 스트림 형식 (빅엔디언, `DataOutputStream` 기준)**

```
stream := MAGIC record*            MAGIC = 5바이트 'G' 'S' 'R' 'A' 0x01 (버전 1)
record := 'F'(0x46) str(path) u32(n) bytes[n]        파일 1개. path = 저장소 루트 기준 상대경로('/' 구분, 저장소명 미포함)
        | 'S'(0x53) str(path) str(reason)            건너뛴 항목 (경로 규칙 위반 등)
        | 'E'(0x45) str(message)                     실패 — 마지막 레코드
        | 'Z'(0x5A) u32(fileCount) u32(skippedCount) u64(totalBytes)   정상 종료 — 마지막 레코드
str    := u32(byteLen) UTF-8 바이트
```

- **오류도 프레임이다**(`success:false` JSON 대신 `'E'`). 응답 형식을 하나로 두어 브라우저 파서가 하나다. 기존 "모든 실패는 200 + 메시지" 원칙(§5)과 같은 의미다. 서버는 **MAGIC 을 먼저 쓰고 flush 한 뒤** 다운로드를 시작한다(헤더가 빨리 가고, 이후 모든 결과가 프레임으로만 나간다).
- 브라우저가 실패로 보는 경우: `res.ok` 아님(401/403 → `location.reload()`, 그 외 경고), MAGIC 불일치(예: 로그인 HTML), `'E'`, `'Z'` 없이 스트림 종료.
- `u32(n)` 파일 크기 상한 100 MiB 라 int 범위 안. `u64` 는 브라우저에서 `DataView.getBigUint64` → `Number`.

### 13.6 화면 (repositories.jsp) — 기존 id/class 유지

| 변경 | 내용 |
|---|---|
| `#btnRepoPull` | id·class·`#btnRepoPullLabel` **그대로**. 라벨만 단계별로 바뀜: "폴더 선택 중…" → "끌어오는 중…" → "저장 중… n/N" → "끌어오기" |
| 신규 `span#repoSaveStatus.repo-save-status` | `#repoSearchForm` 의 `.search-form__actions` **뒤**(폼 아래 한 줄) 또는 커밋 바 안 마지막. 초기 `hidden`. 저장 결과 한 줄 |
| 신규 `static/js/repo-archive.js` | 프레임 파서 + 저장 로직(아래 API). JSP 인라인 스크립트가 이미 300줄이라 분리. `dialog.js` 뒤, 페이지 인라인 스크립트 앞에 로드 |
| `common.css` | `.repo-save-status`(+ `--error` 변형)만 추가 |
| 페이지 설명문 `.page-header__desc` | "끌어오기를 누르면 저장할 폴더를 고르고, 그 아래 저장소 이름 폴더에 파일을 저장합니다 (Chrome·Edge)." 로 갱신(셀렉터 의존 없음) |

`repo-archive.js` 공개 API (전역 `window.repoArchive`):

```js
repoArchive.isSupported()                       // typeof window.showDirectoryPicker === 'function'  ← isSecureContext 로 막지 말 것(e2e 가짜 주입이 동작해야 함)
repoArchive.pickDirectory()                     // showDirectoryPicker({ id:'guisample-repo-pull', mode:'readwrite', startIn:'documents' }) 의 Promise 를 '그대로' 반환(동기 호출)
repoArchive.unsupportedReason()                 // !isSecureContext → '이 주소(http)는 보안 연결이 아니어서 브라우저가 폴더 저장을 허용하지 않습니다. https 또는 localhost 로 접속하세요.'
                                                //  그 외             → 'Chrome 또는 Edge 에서만 폴더 저장을 지원합니다.'
repoArchive.isSafeSegment(name)                 // 비었음·'.'·'..'·'/'·'\\'·NUL 포함 → false
repoArchive.save(dirHandle, repoName, archiveUrl, onProgress)
    // → Promise<{ folderName, repoName, fileCount, skipped:[{path,reason}], failed:[{path,error}], totalBytes }>
    // 'E'·불완전 종료·HTTP 오류는 reject(Error{ message, savedCount })
```

- 경로 재검사(브라우저): `path.split('/')` 의 모든 세그먼트에 `isSafeSegment`, 맨 앞 `/` 거부. 실패 → `skipped` 에 추가하고 계속.
- 파일 하나 쓰기 실패(이름이 OS 에서 불가, 같은 이름의 폴더가 이미 있음 `TypeMismatchError` 등) → `failed` 에 추가하고 **계속**. 저장소 폴더(`{repo}`) 자체를 못 만들면 전체 실패.
- 쓰기는 순차(`await` 하나씩). 폴더 핸들은 `Map<상대경로, DirectoryHandle>` 로 캐시.
- 기본 `alert()`/`confirm()` 금지, `let`, 인라인 style 금지, 사용자 유래 문자열(파일 경로·저장소명)은 `appDialog`(textContent) 또는 `textContent` 로만.

**비보안 주소 문제**: 현재 접속 주소 `http://mytechtest.jihun.com` 에서는 `isSupported()` 가 false 라 항상 B2 경고로 떨어진다. 완료판정을 위한 접속 방법은 13.10 사용자 확인 1.

### 13.7 백엔드 계약 상세

`scm/ScmClient.java` 에 추가 (`putFile`·`findTree` 와 같은 `default` 방식):

```java
/**
 * 커밋(또는 브랜치) 시점의 파일 전체를 하나씩 visitor 에 넘긴다 (저장소끌어오기 저장).
 * 경로는 저장소 루트 기준 상대경로이며 ScmPaths.safeRelativePath 를 통과한 것만 onFile 로 온다.
 * 구현은 서버 디스크에 쓰지 않는다.
 */
default void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException
{
    throw new ScmException("이 저장소는 파일 내려받기를 지원하지 않습니다: " + repoUrl);
}
```

`scm/ScmArchiveVisitor.java` (신규):

```java
public interface ScmArchiveVisitor
{
    /** 파일 1개. content 는 이 항목의 내용만 읽히며, 닫지 않는다(구현이 관리). */
    void onFile(String path, InputStream content) throws IOException;

    /** 규칙 위반 등으로 건너뛴 항목. */
    default void onSkipped(String path, String reason) { }
}
```

`scm/ScmPaths.java` (신규, final 유틸, 저장소 중립):

```java
/** 안전한 상대경로면 정규화된 값('/' 구분), 아니면 null.
 *  거부: null·공백, 맨 앞 '/', 역슬래시, NUL, 빈 세그먼트(연속 '/'), '.', '..', 첫 세그먼트가 'C:' 같은 드라이브 표기. 맨 끝 '/' 는 디렉터리라 null. */
public static String safeRelativePath(String path)
```

`scm/github/GitHubScmClient.java`:

- `readArchive` 구현. 아카이브 전용 `RestClient archiveClient` 를 생성자에서 하나 더 만든다 — 헤더·토큰·리다이렉트(NORMAL)는 기존과 같고 **readTimeout 50초**(13.2 — 총 시간 기준. nginx 기본 `proxy_read_timeout` 60초보다 짧게 잡아 서버가 먼저 명확한 메시지를 낸다). 기존 `restClient`(20초)는 손대지 않는다.
- 호출: `archiveClient.get().uri(uri -> zipballUri(uri, ref, refValue)).exchange((req, res) -> …)` 에서 상태 확인(4xx/5xx → 기존 `toScmException` 과 같은 문구 체계, 404 는 "저장소 또는 커밋을 찾을 수 없음 (비공개 저장소면 토큰 필요)") 후 본문을 `readLimited(res.getBody(), MAX_ARCHIVE_BYTES)` 로 `byte[]` 에 받는다. **codeload 리다이렉트는 JDK HttpClient 가 따른다**(https→https). 비공개 저장소는 GitHub 가 Location 에 단기 토큰을 붙여 주므로 Authorization 헤더 전달 여부와 무관하다(**확인 필요**: 토큰 설정 + 비공개 저장소 1회 수동 확인. 목표 저장소는 공개라 완료판정과 무관).
- `static URI zipballUri(UriBuilder uri, RepoRef ref, String refValue)` — `repos/{owner}/{repo}/zipball` + ref 를 `/` 로 쪼갠 **세그먼트를 URI 변수로**. 빈·`.`·`..` 세그먼트는 `ScmException("커밋/브랜치 이름이 올바르지 않습니다")`. `branchUri` 의 세그먼트 검사와 같은 규칙이므로 private static `refSegments(String)` 로 묶어 둘 다 쓰게 정리해도 된다(동작 불변, 기존 회귀 테스트 유지).
- `static void readZip(InputStream zip, ScmArchiveVisitor visitor) throws IOException` — **순수 함수(단위 테스트 대상)**. `ZipInputStream` 순회, `stripArchiveRoot` 적용, 디렉터리 항목 무시, 경로 검사 실패는 `onSkipped`. visitor 에 넘기는 스트림은 `close()` 를 막은 래퍼(`FilterInputStream` 의 close 무시)로 감싼다.
- `static String stripArchiveRoot(String entryName, String root)` — `root/a/b.txt` → `a/b.txt`, `root/` → `""`(무시), 다른 root·root 없음 → null(건너뜀).
- 상수: `MAX_ARCHIVE_BYTES = 50 MiB`(압축본). 설정화하지 않는다(§0 A8 과 같은 이유 — yaml 이 gitignore 대상이라 새 키는 환경 누락 위험).

`service/RepoArchiveService.java` (신규, `@Service`, `@Transactional` 없음 — DB 미사용):

```java
public static final int  MAX_FILES       = RepoBrowseService.MAX_ROWS;   // 20,000
public static final long MAX_FILE_BYTES  = 100L * 1024 * 1024;          // GitHub 단일 파일 한도와 같음
public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;          // 압축 폭탄 방어

public Result streamArchive(String repoUrl, String ref, RepoArchiveFrameWriter writer) throws IOException
    // 검증 → resolve → client.readArchive(url, ref.trim(), visitor) → Result 반환. writeEnd 는 컨트롤러가 쓴다.
static byte[] readLimited(InputStream in, long max) throws IOException       // 순수, max 초과 시 ScmException
public record Result(int fileCount, int skippedCount, long totalBytes) { }
```

`service/RepoArchiveFrameWriter.java` (신규, 빈 아님, 순수 — 단위 테스트 대상):

```java
public RepoArchiveFrameWriter(OutputStream out)                 // 생성 시 아무것도 쓰지 않음
public void start() throws IOException                          // MAGIC 쓰고 flush
public void writeFile(String path, byte[] content) throws IOException   // 파일마다 flush
public void writeSkipped(String path, String reason) throws IOException
public void writeEnd(int fileCount, int skippedCount, long totalBytes) throws IOException
public void writeError(String message) throws IOException
```

`controller/user/RepositoryRestController.java` 에 추가:

```java
@GetMapping("/archive")
public void archive(@RequestParam(required = false) String repoUrl,
                    @RequestParam(required = false) String ref,
                    HttpServletResponse response)
```

- 헤더 설정(13.5) → `writer = new RepoArchiveFrameWriter(response.getOutputStream())` → `writer.start()` → `streamArchive` → `writeEnd`.
- `catch (Exception e)`: `writer.writeError(message != null ? message : "저장소 파일을 가져오지 못했습니다.")`. 그 쓰기마저 실패(브라우저가 끊음 — `IOException`/`ClientAbortException`)하면 **로그만**. **예외를 밖으로 던지지 않는다** — 응답이 이미 커밋돼 있어 던지면 오류 페이지 HTML 이 프레임 뒤에 섞이거나 로그만 시끄럽다(SSE 경로와 같은 원칙).
- `LoginUserSession` 사용 안 함(소유권 해당 없음). 로그: `repo`, `ref`, `fileCount`, `skippedCount`, `totalBytes`, 소요 시간.
- 동기 MVC(`StreamingResponseBody`·`boundedElastic` 아님) — 비동기 디스패치 타임아웃과 스레드 바인딩 문제를 피한다. 요청 스레드 점유 시간은 다운로드(≤50초) + 브라우저 쓰기 시간.

### 13.8 소유권·데이터

| 경로 | 유형 | 근거 |
|---|---|---|
| `GET /user/repository/archive` | **해당 없음** | 앱 DB 를 읽거나 쓰지 않는다. `user_master.user_name` 으로 거를 대상이 없다. 인증은 `/user/**`(미인증 AJAX 401) |

- **데이터 모델 변경 없음** — 앱 DB(5432)·워크플로우 DB(5433) 모두 DDL·매퍼·DTO 테이블 매핑 없음. 신규 타입은 값 객체·유틸뿐.
- 설정(yaml 두 파일)·의존성(`build.gradle`) **변경 없음**.
- **A9 의 범위가 넓어진다**: 지금까지는 서버 `GITHUB_TOKEN` 으로 읽히는 비공개 저장소의 **파일 목록**이 로그인 사용자 누구에게나 보였고, 이제 **파일 내용 전체**를 받을 수 있다. 13.10 사용자 확인 2.

### 13.9 검증 계획 (추가분)

**단위 테스트 (`./gradlew test`, 네트워크·DB 없음)**

- `src/test/.../scm/ScmPathsTest.java` (신규): 통과 `a.txt`, `src/main/A.java`, `한글/파일.md`, `a b/c`; 거부 `""`, `/etc/passwd`, `../x`, `a/../x`, `a/./b`, `a//b`, `a\\b`, `C:/x`, `a\u0000b`, `dir/`.
- `GitHubScmClientTest` 추가:
  - `zipballUri`: `8a63fb2…`(40자) → `/repos/acme/app/zipball/8a63fb2…`; `feature/x` → `/repos/acme/app/zipball/feature/x`(%2F 아님); `../x`·`a/./b`·`a//b` 거부.
  - `stripArchiveRoot`: 정상·root 자체·다른 root·root 없음.
  - `readZip`: 테스트에서 `ZipOutputStream` 으로 만든 zip — `root/`·`root/src/`(디렉터리, 무시), `root/a.txt`, `root/src/B.java`, `root/../evil.txt`(skipped), `other/x.txt`(skipped), 빈 파일 → onFile 경로·내용·onSkipped 사유 확인. visitor 가 스트림을 닫아도 다음 항목이 읽히는지.
- `src/test/.../service/RepoArchiveFrameWriterTest.java` (신규): `start`→`writeFile`(한글 경로, 0바이트·바이너리 내용)→`writeSkipped`→`writeEnd` 바이트열을 `DataInputStream` 으로 되읽어 일치; `writeError` 형식.
- `src/test/.../service/RepoArchiveServiceTest.java` (신규, 가짜 `ScmClient` — 기존 `RepoSyncServiceTest` 방식): URL·ref 공백 거부, 지원 안 하는 URL(resolver 예외), 파일·건너뜀 개수 집계, `readLimited` 상한 초과 → `ScmException`, 파일 수 상한.
- 기존 가짜 `ScmClient` 두 곳(`RepoSyncServiceTest`·`RepoPushServiceTest`)은 `default` 덕분에 **수정 없이 컴파일**돼야 한다(확인 항목).

**e2e (`e2e/specs/repositories.spec.ts`) — 기존 6건의 셀렉터·기대값은 그대로**

현재 접속 주소가 http 라 실제 `showDirectoryPicker` 는 없고, 있어도 OS 창은 Playwright 가 조작할 수 없다. 그래서 **`page.addInitScript` 로 가짜 `showDirectoryPicker` 를 주입**한다(보안 컨텍스트가 아니어도 window 속성은 정의할 수 있음 — OPFS(`navigator.storage.getDirectory`)도 보안 컨텍스트 전용이라 쓰지 않고 **메모리 가짜 핸들**을 쓴다).

- 가짜 구현(스펙 상단 헬퍼, `addInitScript` 문자열): `window.__fsFake = { mode, files: {} , pickCount: 0 }`. `mode` 는 `'abort'`(AbortError `DOMException`) / `'memory'` / `'none'`(`delete window.showDirectoryPicker`). `memory` 는 `{kind:'directory', name:'e2e-picked'}` 루트에서 `getDirectoryHandle(name,{create})`, `getFileHandle(name,{create})` → `createWritable()` → `write(Uint8Array|Blob|string)`, `close()` 에서 `files['guiSample/src/a.txt'] = 바이트(base64 또는 텍스트)` 로 기록. `keepExistingData` 기본 false(덮어쓰기). 미리 넣은 파일로 덮어쓰기를 검증할 수 있게 `files` 초기값을 받는다.
- **`beforeEach` 기본값은 `'abort'`** → 기존 6건은 "폴더 선택 취소 = 목록만"으로 흘러 **대화상자 없이 기존 기대 그대로 통과**해야 한다(이것이 회귀 확인). 개별 테스트가 다른 모드를 쓰려면 그 뒤에 `addInitScript` 를 하나 더 넣는다(나중 스크립트가 덮어씀).
- 추가 테스트 (GitHub 미호출 — `page.route` 로 `/user/repository/files` 와 `/user/repository/archive` 를 **가짜 응답**으로 채운다. 프레임 바이트는 스펙의 `buildFrames([...])` 헬퍼가 `Buffer` 로 만든다):
  1. **저장 성공·덮어쓰기·경로 방어**: mode `memory`, 미리 `guiSample/README.md = 'old'`. 가짜 archive = `F README.md 'new'`, `F src/a.txt`, `F ../evil.txt`(브라우저 방어 확인용 — 서버는 보내지 않지만 브라우저도 막아야 함), `S x.lnk '…'`, `Z 3 1 n`. 기대: `#appDialog` 알림(is-warn 아님)에 "파일 2개", `files['guiSample/README.md']==='new'`, `files['guiSample/src/a.txt']` 존재, `evil.txt` 가 어떤 키에도 없음, `#repoSaveStatus` 표시. archive 요청 URL 의 `ref` 가 가짜 files 응답의 `summary.commitId` 와 같음.
  2. **폴더 선택 취소**: mode `abort` → archive 요청 0건, `#appDialog` 안 열림, `#repoSaveStatus` 에 "취소", 그리드 표시.
  3. **미지원**: mode `none` → 목록 표시 후 `#appDialog.is-warn` 에 "폴더 저장", archive 요청 0건.
  4. **서버 오류 프레임**: archive = `F a.txt`, `E '저장소를 찾을 수 없음'` → `#appDialog.is-warn` 에 메시지, 그리드는 유지.
  5. **불완전 스트림**: archive = MAGIC + `F a.txt` 만(끝 레코드 없음) → 경고.
  6. **picker 는 트리 조회보다 먼저 호출**: 가짜 picker 가 호출 시점의 `files` 요청 발생 여부를 기록 → 호출 시점에 files 요청 0건(사용자 활성화 계약 회귀 방지).
- **완료판정 `@github` 1건 추가**: mode `memory`, `TARGET_REPO_URL` + `main` → 성공 알림, `files` 키 수 = `'Z'` 의 fileCount(> 300), `guiSample/build.gradle` 존재, 그 바이트 길이 = 트리 응답의 `build.gradle` 행 `size`(**그리드와 저장 내용이 같은 커밋**인지 확인), `guiSample/src/main/...` 깊은 경로 존재. GitHub 호출은 트리 3 + zipball 1.
- `afterEach` 기본 대화상자 감시 유지.

**실제 파일로의 완료판정 (수동, QA/오케스트레이터)** — Playwright 는 OS 폴더 창을 조작할 수 없으므로 사람 손이 한 번 필요하다.
1. 13.10-1 에서 정한 방법으로 보안 컨텍스트 접속(Chrome).
2. `/user/repositories` → URL `https://github.com/sunjihun73/guiSample.git`, 브랜치 `main`(그리고 `dev` 1회) → 끌어오기 → 임시 폴더(예: `~/Documents/pulltest`) 선택 → "편집 허용" 승인.
3. 확인: `find ~/Documents/pulltest/guiSample -type f | wc -l` = 알림의 파일 수; `git -C <로컬 클론> archive <commitId> | tar -t` 또는 `curl -L …/zipball/<commitId>` 의 파일 수·`shasum` 몇 개 비교; 같은 폴더로 한 번 더 끌어오기 → 오류 없이 덮어씀(파일 수 동일, 수정 시각 갱신).

**매퍼**: 해당 없음.

### 13.10 사용자 확인이 필요한 결정 (오케스트레이터 전달용)

1. **[확인 필요 — 완료판정을 막는 환경 문제] 보안 컨텍스트**. 앱이 `http://mytechtest.jihun.com`(nginx :80)으로 서비스되므로 Chrome/Edge 에서도 `showDirectoryPicker` 가 **존재하지 않는다**. 코드로 해결할 수 없다. 선택지:
   - (a) **Chrome 플래그**(권장, 코드·인프라 변경 없음): `chrome://flags/#unsafely-treat-insecure-origin-as-secure` 에 `http://mytechtest.jihun.com` 추가 후 재시작. 사용자 PC 브라우저 1대에만 적용된다.
   - (b) `http://localhost:8080` 으로 직접 접속(localhost 는 보안 컨텍스트). Keycloak 클라이언트의 Valid Redirect URIs 에 `http://localhost:8080/*` 가 있어야 한다(**확인 필요**).
   - (c) nginx 에 TLS(mkcert 등) — Keycloak·LiteLLM·e2e `APP_URL`·`forward-headers` 까지 영향이 커 이번 범위 밖으로 제안.
   설계는 어느 경우든 동작하게 되어 있고(기능 감지 + 안내 문구), e2e 는 가짜 주입이라 영향 없다.
2. **[확인 필요] 비공개 저장소 내용 다운로드 허용 여부** — A9 에서 "목록 공개"를 허용했는데, 이번엔 서버 토큰으로 **내용 전체**를 누구나 받을 수 있다. 기본안은 "A9 와 같이 허용". 막으려면 `readArchive` 전에 저장소 메타의 `private=true` 면 거부(호출 1회 추가).
3. (참고) 덮어쓰기만 하고 저장소에 없는 로컬 파일은 지우지 않는다(B3). 미러링이 필요하면 별도 요구로.

### 13.11 ADR 변경 필요 여부

**ADR 결정 변경 필요 없음 — ADR 0007 에 "결정 상세 10" 추가 기록만.** clone 하지 않는다(API 1회로 zip), 라이브러리 추가 없음(`java.util.zip`·`RestClient`), `ScmClient` 에 `default` 메서드 하나(`readArchive`), 소유권 해당 없음은 모두 기존 결정의 연장이다. 기록할 것: 프레임 형식, 메모리 버퍼링(서버 디스크 미사용)과 상한(50/100/500 MiB, 2만 파일), 아카이브 전용 타임아웃 근거(13.2 Spring 소스), 링크·서브모듈·빈 폴더 처리, 보안 컨텍스트 제약, 비공개 저장소 내용 노출(감수하는 것), 레이트 한도(끌어오기 1회당 최대 4회).

### 13.12 분배 지시 (후속 1)

#### mybatis-data-engineer
**해당 없음** — DDL·매퍼·DB 변경 없음(앱 DB 5432, 워크플로우 DB 5433 모두).

#### spring-backend-engineer
1. `src/main/java/kr/co/jihun/guisample/scm/ScmArchiveVisitor.java` 신규 (13.7 시그니처).
2. `src/main/java/kr/co/jihun/guisample/scm/ScmPaths.java` 신규 — `public static String safeRelativePath(String)`.
3. `scm/ScmClient.java` — `default void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException` 추가(기본 구현 `ScmException`). 클래스 javadoc 에 저장소끌어오기 저장 언급. 기존 메서드 불변.
4. `scm/github/GitHubScmClient.java` — 아카이브 전용 `RestClient`(readTimeout 50초), `readArchive`, package-private static `zipballUri`·`stripArchiveRoot`·`readZip`, 상수 `MAX_ARCHIVE_BYTES`. `branchUri` 와 세그먼트 검사 공유는 선택(기존 테스트 통과 유지). 클래스 javadoc 의 API 목록에 zipball 추가.
5. `service/RepoArchiveFrameWriter.java` 신규 (13.7, 13.5 형식 그대로 — 프론트·e2e 가 이 바이트 형식에 의존한다).
6. `service/RepoArchiveService.java` 신규 — `streamArchive`, `readLimited`, 상수 3개, `Result` record.
7. `controller/user/RepositoryRestController.java` — `GET /archive` 추가(13.7). 기존 `files` 불변.
8. 단위 테스트 4종(13.9). `./gradlew test` 전체 통과, 기존 가짜 `ScmClient` 무수정 컴파일 확인.
9. **실제 호출 1회 확인**(앱 기동 후 `curl -s -b <세션쿠키> -H 'X-Requested-With: XMLHttpRequest' 'http://mytechtest.jihun.com/user/repository/archive?repoUrl=https://github.com/sunjihun73/guiSample.git&ref=<commitId>' | head -c 5 | xxd` → `GSRA\x01`, 끝에서 `'Z'` 와 fileCount ≈ 380) — 또는 동등한 방법. 결과를 `_workspace/01_backend_summary.md` 에 "후속 1" 절로 추가.
10. yaml 두 파일·`build.gradle` 변경 없음.

#### jsp-frontend-engineer
1. `src/main/resources/static/js/repo-archive.js` 신규 — 13.6 공개 API, 13.5 프레임 파서(`ReadableStream` reader + 정확히 n바이트 읽기 버퍼, `DataView` 빅엔디언, `TextDecoder('utf-8')`), 경로 재검사, 순차 쓰기·폴더 핸들 캐시, 401/403 → `location.reload()`.
2. `src/main/webapp/WEB-INF/views/user/repositories.jsp` — `repo-archive.js` 로드(`dialog.js` 뒤), `pullRepository` 를 13.4 순서로 고침. **`pickDirectory()` 호출은 URL 검사·중복 클릭 검사 직후, 어떤 `await`/ajax/appDialog 보다 먼저.** `#repoSaveStatus` 추가, 설명문 갱신. 기존 id/class 유지, 점검완료 버튼 그대로(핸들러 없음). archive URL 의 `ref` 는 `summary.commitId`, 폴더명은 `summary.repo`(`isSafeSegment` 실패 시 저장 생략 + 경고).
3. `src/main/resources/static/css/common.css` — `.repo-save-status`, `.repo-save-status--error` 만 추가.
4. `e2e/specs/repositories.spec.ts` — 13.9 e2e 전부: 가짜 picker 헬퍼·`beforeEach` 기본 `'abort'`·프레임 빌더 헬퍼·추가 테스트 6건 + `@github` 1건. 상단 주석 셀렉터 목록에 `#repoSaveStatus`, 가짜 picker 사용법, 프레임 형식 요약 추가. `package.json` 변경 없음.
5. 결과를 `_workspace/03_frontend_summary.md` 에 "후속 1" 절로 추가.

#### qa (경계면 확인 포인트)
1. **바이트 형식**: `RepoArchiveFrameWriter` ↔ `repo-archive.js` 파서 ↔ e2e `buildFrames` 세 곳이 13.5 와 같다(MAGIC 5바이트, 빅엔디언, 레코드 타입 바이트, `Z` 의 u32/u32/u64).
2. **사용자 활성화**: `repositories.jsp` 에서 `showDirectoryPicker` 호출 전 `await`·`$.ajax`·`appDialog` 가 없는지(코드 리딩 + e2e 6번).
3. **기능 감지**: `isSupported()` 가 `isSecureContext` 로 막지 않는지(막으면 e2e 가짜가 무력화됨).
4. **기존 회귀**: 기존 6건이 `'abort'` 기본값에서 기대 그대로 통과, 기존 id/class 불변, `GET /files` 응답 불변.
5. **경로 방어 이중화**: 서버 `ScmPaths`·`stripArchiveRoot` 와 브라우저 `isSafeSegment` 가 같은 규칙(`..`, `.`, 빈 세그먼트, 역슬래시, 맨 앞 `/`).
6. **예외 미전파**: `archive` 핸들러가 어떤 경우에도 예외를 던지지 않음(응답 커밋 후 오류 HTML 혼입 없음), 오류는 `'E'`.
7. **타임아웃**: 아카이브는 별도 RestClient(50초), 기존 `restClient` 20초 불변.
8. 단위 테스트 실행(`./gradlew test`), e2e 는 Phase 3 에서 `npm test`(+ `@github`).
9. 13.9 수동 완료판정 절차를 13.10-1 결정 후 실행 — **Playwright 통과만으로 완료판정 대체 불가**(OS 창·실제 디스크 쓰기는 사람이 확인).

#### 문서 (오케스트레이터/마지막 단계)
- ADR 0007: 맥락 단락·구성 요소 표(`ScmArchiveVisitor`, `ScmPaths`, `RepoArchiveService`, `RepoArchiveFrameWriter`, `repo-archive.js`, 테스트)·REST 표·"결정 상세 10"·실측 표·감수하는 것(비공개 내용 노출, 보안 컨텍스트, 덮어쓰기만·삭제 안 함, 한도 4회)·변경 이력.
- `CLAUDE.md` 저장소끌어오기 단락(저장 흐름, picker 선호출 규칙, 프레임 형식 위치, 보안 컨텍스트 제약, e2e 가짜 picker), 변경 이력 행.
- `README.md` 화면·REST API 표(`GET /user/repository/archive`).
