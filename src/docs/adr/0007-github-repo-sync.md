# 0007. REPO동기화 — 저장소 커밋으로 프로젝트 변경 파일 채우기 (GitHub 우선, SCM 추상화)

- 상태(Status): 승인됨(Accepted)
- 날짜(Date): 2026-10-13

## 배경(Context)

`project_master` 에 `req_id`(요청 ID), `repo_url`(저장소 URL), `file_names`(변경 파일 목록) 컬럼이 추가됐다. `file_names` 는 화면에서 직접 입력할 수도 있지만, 실제 값은 저장소에 이미 있다. 개발자는 커밋 메시지에 `{REQ-123}` 처럼 **중괄호로 요청 ID 를 적는다**. 그렇다면 커밋을 읽어 그 요청에 걸린 파일을 자동으로 모을 수 있다.

요구사항은 다음과 같다.

1. 프로젝트의 `repo_url` 저장소에서 **최근 30일** 커밋을 조회한다.
2. 커밋 메시지의 `{…}` 값이 프로젝트의 `req_id` 와 같은 커밋을 찾는다.
3. 그 커밋들이 바꾼 파일명을 `file_names` 에 반영한다.
4. 프로젝트 목록 화면의 **REPO동기화** 버튼으로 실행한다.

제약이 하나 있다. **지금은 GitHub 이지만 다른 저장소(GitLab, 사내 Git, SVN)로 바뀌거나 늘어날 수 있다.** 이번 구현 대상은 GitHub 뿐이지만, 저장소를 바꿀 때 동기화 로직까지 다시 짜게 만들면 안 된다.

## 결정(Decision)

저장소 조회를 **`ScmClient` 인터페이스** 뒤로 숨기고, 동기화 규칙(`RepoSyncService`)은 그 인터페이스만 보게 한다. 저장소 종류는 **`repo_url` 을 보고 구현체가 스스로 판단**(`supports`)하며 `ScmClientResolver` 가 고른다. 이번 구현체는 GitHub REST API 를 **라이브러리 없이 Spring `RestClient`** 로 호출하는 `GitHubScmClient` 하나다.

동기화는 **소유자와 무관하게 전체 프로젝트**를 대상으로 한다. 이것은 [데이터 소유권 규칙](../../../CLAUDE.md)의 의도된 예외다(결정 상세 7).

## 구성 요소

| 역할 | 파일 |
|---|---|
| 저장소 조회 계약 | `scm/ScmClient.java` |
| 저장소 중립 커밋 요약 | `scm/ScmCommit.java` |
| 구현체 선택 | `scm/ScmClientResolver.java` |
| 조회 실패 예외 | `scm/ScmException.java` |
| GitHub 구현 | `scm/github/GitHubScmClient.java` |
| GitHub 설정 바인딩 | `scm/github/GitHubProperties.java` (`scm.github.*`) |
| 동기화 규칙 | `service/RepoSyncService.java` |
| 응답 DTO | `dto/RepoSyncResult.java` |
| REST | `controller/user/ProjectMasterRestController.java` (`syncRepository`) |
| 매퍼 | `mapper/ProjectMasterMapper.java`, `resources/mapper/ProjectMasterMapper.xml` (`updateProjectFileNames`) |
| 화면 | `views/user/projects.jsp` (`#btnRepoSync`) |
| 설정 | `application-local.yaml`, `application-e2e.yaml` 의 `scm:` 블록 |
| 단위 테스트 | `src/test/.../service/RepoSyncServiceTest.java`, `src/test/.../scm/github/GitHubScmClientTest.java` |
| 회귀 테스트 | `e2e/specs/projects.spec.ts` (REPO동기화 버튼) |

## 전체 흐름

```
[브라우저] /user/projects — REPO동기화 버튼
     │  POST /user/project/projects/repo-sync
     ▼
[앱] ProjectMasterRestController — LoginUserSession 에서 user_name 을 꺼낸다 (update_user_id 기록용)
     ▼
[앱] RepoSyncService.syncFileNames(userName)
     ├─ selectProjectMasterList({})  ── 전체 프로젝트 (소유자 조건·페이징 없음)
     ├─ repo_url·req_id 가 둘 다 있는 것만 남기고 repo_url 로 묶는다
     │
     └─ 저장소마다
          ├─ ScmClientResolver.resolve(repoUrl) ─► GitHubScmClient (supports = 호스트가 github.com)
          ├─ findCommitsSince(repoUrl, now - 30d)  ─► GET /repos/{o}/{r}/commits?since&per_page=100&page=n
          ├─ 커밋 메시지에서 {…} 추출 → req_id → 커밋 목록 색인
          └─ 프로젝트마다
               ├─ 일치 커밋 없음 → NO_MATCH (file_names 그대로)
               ├─ 일치 커밋마다 findChangedFiles ─► GET /repos/{o}/{r}/commits/{sha}  (커밋당 1회 캐시)
               ├─ 파일 합집합·정렬·줄바꿈 join
               ├─ 기존 값과 같음 → UNCHANGED
               └─ 다름 → updateProjectFileNames(projectId, fileNames, userName) → UPDATED
     ▼
[브라우저] 결과 요약 alert + 그리드 재조회
```

### REST 계약

| 메서드 | 경로 | 응답 |
|---|---|---|
| POST | `/user/project/projects/repo-sync` | `{ success, result: RepoSyncResult }` 또는 `{ success: false, message }` |

`RepoSyncResult`:

```json
{
  "lookbackDays": 30,
  "total": 4, "updated": 1, "unchanged": 0, "noMatch": 0, "failed": 3,
  "items": [
    { "projectId": "…", "projectName": "…", "reqId": "#110517",
      "status": "UPDATED", "commitCount": 1, "fileCount": 2, "message": null }
  ]
}
```

`status` 는 `UPDATED | UNCHANGED | NO_MATCH | FAILED`. **저장소 하나가 실패해도 `success` 는 `true`** 이고 실패는 항목의 `FAILED` + `message` 로 돌아온다. `success: false` 는 세션이 없는 경우처럼 동기화 자체를 시작하지 못했을 때뿐이다.

## 결정 상세

### 1. 저장소 추상화 — `ScmClient` + URL 기반 선택

```java
public interface ScmClient {
    boolean supports(String repoUrl);
    List<ScmCommit> findCommitsSince(String repoUrl, Instant since);
    List<String> findChangedFiles(String repoUrl, String commitId);
}
```

- **구현체 추가만으로 확장된다.** `ScmClientResolver` 는 `List<ScmClient>` 를 주입받아 `supports` 가 참인 첫 구현을 쓴다. SVN 을 붙이려면 `scm/svn/SvnScmClient` 를 `@Component` 로 추가하면 되고, `RepoSyncService`·컨트롤러·화면은 그대로다.
- **커밋 목록과 변경 파일을 나눈 이유.** GitHub 목록 API 에는 파일이 없어서 커밋마다 상세를 따로 읽어야 한다. 나눠 두면 **req_id 가 일치한 커밋만** 상세를 읽는다(30일 커밋이 수백 건이어도 상세 호출은 일치 건수만큼). 반대로 SVN 은 `svn log -v` 한 번에 경로까지 오므로, 구현체가 목록 조회 때 읽은 경로를 들고 있다가 `findChangedFiles` 에서 돌려주면 된다. 계약이 두 저장소 모두에 무리가 없다.
- **`ScmCommit` 은 `id` 와 `message` 만 갖는다.** Git 의 SHA, SVN 의 리비전 번호를 같은 `String id` 로 받는다. 작성자·시각 같은 필드는 쓰는 곳이 없어 넣지 않았다. 필요해지면 그때 추가한다.

| 후보 | 기각 사유 |
|---|---|
| `RepoSyncService` 안에서 GitHub API 직접 호출 | 저장소를 바꿀 때 매칭 규칙까지 다시 손대야 한다. 요구사항의 확장성 조건 위반 |
| `project_master` 에 `repo_type` 컬럼을 두고 분기 | 스키마 변경과 화면 입력 항목이 하나 더 는다. URL 만으로 구분되는 현재는 과하다(한계는 "감수하는 것" 참조) |
| JGit 으로 clone 후 `git log` | 동기화마다 저장소 전체(또는 shallow) 클론이 필요하다. 디스크·시간 비용이 크고 Git 전용이라 SVN 확장에 도움이 안 된다 |

### 2. GitHub 호출 — 라이브러리 대신 Spring `RestClient`

요구사항에 "필요한 경우 github 라이브러리 추가"가 있었으나 **추가하지 않았다.**

| 후보 | 판단 |
|---|---|
| hub4j `org.kohsuke:github-api` | 사실상 표준 Java 클라이언트지만 **Jackson 2** 와 자체 HTTP 커넥터를 끌고 온다. 이 앱은 Spring Boot 4(**Jackson 3**, `tools.jackson`)라 두 Jackson 이 공존하게 되고, Boot 4 와의 호환도 검증되지 않았다 |
| **Spring `RestClient`** (채택) | 필요한 API 가 **커밋 목록·커밋 상세 두 개**뿐이다. 이미 클래스패스에 있고 Boot 의 Jackson 3 로 바로 매핑된다. 응답 DTO 는 필요한 필드만 가진 record(`@JsonIgnoreProperties(ignoreUnknown = true)`) |

호출 세부:

- 헤더 `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`. 토큰이 있으면 `Authorization: Bearer …`.
- 타임아웃 connect 5초 / read 20초 (`JdkClientHttpRequestFactory`). 리다이렉트를 따른다 — 이름이 바뀐 저장소는 301 로 옮겨 준다.
- 목록·상세 모두 `per_page=100` 으로 페이지를 넘기며, 한 페이지가 덜 차면 멈춘다. 상한은 `scm.github.max-pages`(10).
- HTTP 오류는 사람이 읽을 수 있는 `ScmException` 으로 바꾼다: 401 인증 실패, 403/429 접근 거부·한도 초과, 404 저장소 없음(비공개면 토큰 필요), 409 빈 저장소.
- URL 파싱: `https://host/owner/repo(.git)(/…)` 와 `git@host:owner/repo(.git)`. `supports` 는 호스트가 `scm.github.web-host` 와 같을 때(대소문자 무시)만 참이다. GitHub Enterprise 는 `api-base-url`·`web-host` 만 바꾸면 된다.

### 3. 매칭 규칙 — `{…}` 정확히 일치

- 정규식 `\{([^{}]+)}` 로 메시지 안의 **모든** 중괄호 값을 꺼낸다. 앞뒤 공백은 지우고, 빈 값은 버리고, 같은 메시지 안의 중복은 하나로 친다.
- `req_id`(앞뒤 공백 제거)와 **대소문자까지 정확히 같아야** 일치다. 부분 일치나 대소문자 무시는 하지 않는다 — `REQ-1` 이 `REQ-10` 커밋을 끌어오는 오탐을 막기 위해서다.
- 중첩 중괄호(`{a{b}}`)는 안쪽 `{b}` 만 잡힌다. 요청 ID 에 중괄호가 들어갈 일은 없다고 본다.

### 4. `file_names` 갱신 규칙 — 교체, 단 일치가 없으면 유지

- 일치 커밋들의 변경 파일 **합집합**을 **정렬**하고 **줄바꿈(`\n`)** 으로 이어 `file_names` 를 **교체**한다. 기존 값에 덧붙이지 않는다 — 덧붙이면 동기화할 때마다 커지고 지워진 파일이 남는다.
- **일치 커밋이 0건이면 손대지 않는다(`NO_MATCH`).** 30일이 지나 커밋이 조회 범위를 벗어나도 지난 동기화 결과나 사람이 직접 적은 값이 지워지지 않게 하기 위해서다.
- 새 값이 기존 값과 같으면 UPDATE 하지 않는다(`UNCHANGED`) — `update_date` 가 의미 없이 바뀌지 않게 한다.
- 정렬은 결과를 결정적으로 만든다. 커밋 순서가 달라도 같은 집합이면 같은 문자열이 되어 `UNCHANGED` 판정이 성립한다.
- 파일 경로는 저장소 루트 기준 상대 경로 그대로다(예: `deps/v8/src/base/memcopy.h`). 삭제된 파일도 해당 커밋이 "바꾼" 파일이므로 포함된다.

### 5. 조회 범위 — 최근 30일, 기본 브랜치

- `scm.sync.lookback-days`(기본 30)로 `since = 지금 - N일` 을 계산해 저장소에 넘긴다. 시각은 `Clock` 으로 주입받아 테스트에서 고정한다.
- GitHub 커밋 목록 API 의 기본값대로 **기본 브랜치**만 본다. 기능 브랜치에만 있는 커밋은 병합되기 전까지 잡히지 않는다.
- 같은 `repo_url` 을 쓰는 프로젝트가 여럿이면 **커밋 목록은 저장소당 한 번**만 읽고, 같은 커밋의 파일 목록도 **커밋당 한 번**만 읽는다.

### 6. 트랜잭션 없음, 부분 실패 허용

- 외부 HTTP 를 기다리는 동안 DB 커넥션을 잡지 않도록 `@Transactional` 을 걸지 않고 **프로젝트 단위로 바로 UPDATE** 한다. 동기화는 다시 눌러도 같은 결과가 나오는(멱등) 작업이라 중간 실패 후 재실행으로 충분하다.
- 저장소 조회가 실패하면 그 저장소의 프로젝트만 `FAILED` 가 되고 나머지 저장소는 계속 처리한다. 지원하지 않는 URL(`gitlab.com`, `svn://…`, 형식이 아닌 문자열)도 같은 경로로 `FAILED` + "지원하지 않는 저장소 URL" 이 된다.

### 7. 소유권 — 전체 프로젝트 대상 (소유권 규칙의 예외)

처음 구현은 다른 도메인처럼 로그인 사용자 소유 프로젝트(`create_user_id = user_name`)만 대상으로 하고 UPDATE 에도 소유자 조건을 걸었다. **요청에 따라 둘 다 제거했다.**

- 대상 조회: `selectProjectMasterList` 에 `createUserId` 를 넣지 않는다 → 전체 프로젝트.
- 결과 저장: `updateProjectFileNames` 의 WHERE 는 `project_id` 뿐이다.
- `update_user_id` 에는 **동기화를 실행한 사용자**를 기록한다. 검증이 아니라 감사(누가 마지막으로 바꿨는지) 목적이다.

`file_names` 는 저장소라는 공유된 사실에서 기계적으로 계산되는 값이라, 누가 실행해도 같은 결과가 나온다. 그래서 소유자 제한 없이 한 번에 전체를 맞추는 쪽을 택했다. 그 대가는 "감수하는 것"에 적었다.

## 실측 근거

2026-10-13 로컬 환경(토큰 없음, 공개 저장소)에서 확인했다.

| 확인 항목 | 방법 | 결과 |
|---|---|---|
| GitHub 경로 끝까지 동작 | `repo_url=https://github.com/nodejs/node.git`, `req_id=#110517` 프로젝트로 동기화 | `UPDATED`, 커밋 1건, `file_names = common.gypi\ndeps/v8/src/base/memcopy.h` |
| 결과가 실제 커밋과 같은지 | GitHub API 로 `{#110517}` 커밋(`e7fcd60…`, "deps: V8: cherry-pick 8d14d49c4040") 직접 조회 | 파일 2개 일치 |
| 다른 사용자 프로젝트도 갱신되는지 | `create_user_id=tmp-other-user` 행을 넣고 e2e 계정으로 동기화 | `UPDATED`, `update_user_id = sunjeehun`(실행자) |
| 지원하지 않는 URL 처리 | 기존 e2e 데이터(`https://git.example.com/…`, 임의 문자열) | 해당 항목만 `FAILED`, 나머지 정상 |
| 매칭·갱신 규칙 | `./gradlew test` — 가짜 `ScmClient` 로 `RepoSyncService` 검증 | 8건 통과 |
| URL 파싱·호스트 판정 | `GitHubScmClientTest` | 2건 통과 |
| 화면 회귀 | `e2e/specs/projects.spec.ts` | 6건 통과 |

실측용 임시 프로젝트 행은 확인 뒤 삭제했다(남겨 두면 e2e 실행마다 GitHub 를 호출한다).

## 결과(Consequences)

### 좋은 점

- 저장소를 바꾸거나 늘릴 때 **구현체 하나만 추가**하면 된다. 매칭·갱신 규칙은 저장소를 모른다(단위 테스트가 가짜 `ScmClient` 로 이를 직접 보여 준다).
- 새 외부 의존성이 없다. Jackson 버전 충돌 위험이 없다.
- 상세 API 는 일치한 커밋만 호출하므로 호출 수가 "30일 커밋 수"가 아니라 "일치 커밋 수"에 비례한다.
- 동기화가 멱등이다. 몇 번을 눌러도 같은 커밋 집합이면 같은 값이 되고, 같으면 UPDATE 도 일어나지 않는다.

### 감수하는 것

- **아무 로그인 사용자나 전체 프로젝트의 `file_names` 를 바꾼다.** 결과 alert 에 다른 사용자 프로젝트의 이름·`req_id` 가 보인다. 소유권 규칙으로 돌아가려면 `RepoSyncService` 의 조회 param 에 `createUserId` 를, `updateProjectFileNames` 의 WHERE 에 `create_user_id` 조건을 다시 넣는다.
- **동기 처리다.** 버튼을 누르면 모든 저장소 조회가 끝날 때까지 HTTP 요청이 열려 있다. 대상 저장소가 많아져 수십 초를 넘기면 비동기 작업(작업 ID + 폴링)이나 스케줄러로 옮겨야 한다.
- **`max-pages`(10페이지 = 1000커밋) 를 넘는 저장소는 오래된 커밋이 조용히 잘린다.** GitHub 는 최신순으로 주므로 30일 범위 중 오래된 쪽이 빠진다. 30일에 1000커밋을 넘는 저장소를 다룰 때 값을 올릴 것.
- **저장소 종류 판정이 URL 에만 의존한다.** 같은 `https://` 호스트로 Git 과 SVN 을 함께 서비스하는 사내 서버라면 URL 만으로 구분이 안 된다. 그때는 `repo_type` 컬럼이나 호스트별 설정을 도입해야 한다.
- 기본 브랜치만 보므로 병합 전 커밋은 반영되지 않는다.
- 토큰 없이 호출하면 **시간당 60회**(IP 기준) 한도다. 저장소당 목록 1~10회 + 일치 커밋 수만큼 상세 호출이 들어가므로 프로젝트가 많으면 금방 찬다.

## 운영 주의

- **`GITHUB_TOKEN`** 을 `.env` 에 넣는다(선택). 없으면 공개 저장소만, 시간당 60회. 있으면 시간당 5,000회이며 토큰이 읽을 수 있는 비공개 저장소도 된다. 권한은 대상 저장소의 **Contents: read** 면 충분하다(fine-grained PAT 기준).
- `application-local.yaml` / `application-e2e.yaml` 은 **gitignore 대상**이다. `scm:` 블록이 커밋에 들어가지 않으므로 새 환경에는 직접 넣어야 한다. 빠지면 `GitHubProperties` 값이 비어 `web-host` 가 null 이 되고, 모든 GitHub URL 이 "지원하지 않는 저장소 URL" 로 `FAILED` 처리된다(오류 원인이 설정 누락이라는 것이 드러나지 않으니 주의).

  ```yaml
  scm:
    sync:
      lookback-days: 30
    github:
      api-base-url: https://api.github.com
      web-host: github.com
      token: ${GITHUB_TOKEN:}
      max-pages: 10
  ```

- 한도 초과(403/429)는 해당 저장소 항목의 `FAILED` 메시지로 보인다. 잠시 뒤 다시 누르면 된다.
- e2e 의 REPO동기화 테스트는 계정의 기존 데이터에 따라 결과 건수가 달라지므로 **호출 성공과 응답 shape 만** 본다. 실제 GitHub 저장소를 가리키는 프로젝트를 e2e 계정에 남겨 두면 실행마다 GitHub 를 호출하게 되니 남기지 말 것.

## 확장 가이드 — SVN 을 붙일 때

1. `scm/svn/SvnScmClient implements ScmClient` 를 `@Component` 로 만든다.
2. `supports`: `svn://`, `svn+ssh://` 또는 설정에 등록된 SVN 호스트의 URL 이면 참.
3. `findCommitsSince`: `svn log -v --xml -r {since}:HEAD <url>`(또는 SVNKit)로 로그를 읽고, 리비전 번호를 `ScmCommit.id` 로 쓴다. `-v` 로 함께 온 경로는 구현체 안에서 리비전별로 들고 있는다.
4. `findChangedFiles`: 3에서 들고 있던 경로를 돌려준다. SVN 경로는 `/trunk/src/A.java` 처럼 저장소 루트 기준이므로, GitHub 와 맞추려면 브랜치 접두사(`/trunk/`)를 떼는 규칙을 정할 것.
5. 설정은 `scm.svn.*` 로 두고 두 프로파일 yaml 에 함께 넣는다.

`RepoSyncService`, 컨트롤러, 화면, 매퍼는 고치지 않는다. `RepoSyncServiceTest` 의 가짜 구현이 이 계약의 예시다.

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-13 | 최초 작성 — `ScmClient` 추상화, GitHub `RestClient` 구현, `{req_id}` 매칭, 교체/유지 규칙 |
| 2026-10-13 | 사용자 이름(소유자) 조건 제거 — 전체 프로젝트 대상, `update_user_id` 는 실행자 기록 |

## 관련 문서

- [0003. Keycloak SSO·세션 관리](0003-keycloak.md) — `LoginUserSession`(실행자 `user_name` 의 출처)
- [0005. 하이브리드 검색·RAG 소유권](0005-hybrid-bm25-rrf.md) — 소유권 규칙의 선례. 이 문서가 REPO동기화에 한해 그 규칙을 깬다
- [0006. 워크플로우 기능 아키텍처](0006-flowable-workflow.md) — 문서 형식의 기준
