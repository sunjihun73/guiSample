# 백엔드 요약: 저장소끌어오기 (2026-10-19, spring-backend-engineer)

설계 `_workspace/00_architect_design.md` §4·§5·§7·§8(1~9)를 구현했고, §12(점검완료 미구현)를 따랐다. 백엔드에는 점검완료 관련 코드가 없다. DB·매퍼·yaml·의존성 변경 없음.

## 파일

| 구분 | 경로 |
|---|---|
| 신규 | `src/main/java/kr/co/jihun/guisample/scm/ScmEntryType.java` (enum DIR/FILE/SYMLINK/SUBMODULE) |
| 신규 | `src/main/java/kr/co/jihun/guisample/scm/ScmTree.java` (record) |
| 신규 | `src/main/java/kr/co/jihun/guisample/scm/ScmTreeEntry.java` (record) |
| 수정 | `src/main/java/kr/co/jihun/guisample/scm/ScmClient.java`: `default ScmTree findTree(String repoUrl, String branch)` 추가. 기본 구현은 ScmException |
| 수정 | `src/main/java/kr/co/jihun/guisample/scm/github/GitHubScmClient.java`: `findTree` 구현(3회 호출), `findBranch`, package-private static `branchUri`·`entryType`·`toEntry`·`webUrl`, 응답 record(RepoMeta, BranchInfo, BranchCommit, CommitData, GitActor, TreeRef, TreeResponse, TreeItem) |
| 신규 | `src/main/java/kr/co/jihun/guisample/dto/RepoFileRow.java` |
| 신규 | `src/main/java/kr/co/jihun/guisample/dto/RepoTreeSummary.java` |
| 신규 | `src/main/java/kr/co/jihun/guisample/service/RepoBrowseService.java`: `browse`, static `toRows(entries, maxRows)`, static `ownerAndRepo`, `MAX_ROWS = 20_000`, 결과 record `Result(summary, rows)` |
| 신규 | `src/main/java/kr/co/jihun/guisample/controller/user/RepositoryRestController.java` |
| 수정 | `src/main/java/kr/co/jihun/guisample/controller/user/MainController.java`: `GET /user/repositories` 를 `user/repositories` 로 라우팅 |
| 신규 | `src/test/java/kr/co/jihun/guisample/service/RepoBrowseServiceTest.java` (9건) |
| 수정 | `src/test/java/kr/co/jihun/guisample/scm/github/GitHubScmClientTest.java` (7건 추가, 전체 11건) |

## 엔드포인트

| Method | Path | 요청 | 응답 | 소유권 |
|---|---|---|---|---|
| GET | `/user/repositories` | - | JSP `user/repositories` | 해당 없음 |
| GET | `/user/repository/files` | query `repoUrl`(필수이지만 `required=false` 로 받는다. 비면 실패 응답), `branch`(선택. 비면 기본 브랜치) | 아래 JSON, **항상 HTTP 200** | 해당 없음. 앱 DB 를 쓰지 않으므로 `LoginUserSession` 을 쓰지 않는다 |

## 응답 shape

- 성공: `success:true, page:1, total:1, records:int, rows:RepoFileRow[], summary:RepoTreeSummary` (message 키 없음)
- 실패: `success:false, message:string(null 아님), page:1, total:0, records:0, rows:[]` (summary 키 없음)
- `RepoFileRow`: `path` string(id), `name` string, `parentPath` string(루트 바로 아래면 `""`), `type` `"DIR"|"FILE"|"SYMLINK"|"SUBMODULE"`, `size` number|null(FILE 만 숫자), `depth` int(루트 바로 아래 0), `webUrl` string|null(SUBMODULE 은 null, DIR 은 `/tree/{sha}/…`, FILE·SYMLINK 는 `/blob/{sha}/…`, 세그먼트 인코딩됨)
- 정렬: parentPath, 그다음 폴더(DIR·SUBMODULE) 먼저, 그다음 name 대소문자 무시, 그다음 name
- `RepoTreeSummary`: `repoUrl`, `owner`(null 가능), `repo`(null 가능), `branch`, `defaultBranch`, `commitId`(40자), `commitMessage`, `commitDate`(ISO-8601 UTC **문자열**, null 가능), `truncated`(GitHub 쪽 잘림 또는 서버 2만 행 상한), `entryCount`(= records)

## GitHub 호출 (findTree)

1. `GET /repos/{o}/{r}` 로 기본 브랜치를 얻는다. 404 면 기존 문구 "저장소를 찾을 수 없음 (비공개 저장소면 토큰 필요)"
2. `GET /repos/{o}/{r}/branches/{seg...}`: 브랜치를 슬래시로 쪼개 pathSegment + URI 변수로 넣는다. 사용자가 지정한 브랜치가 404 면 "브랜치 '{b}' 를 찾을 수 없습니다", 기본 브랜치가 404 이거나 409 면 "커밋이 없는 빈 저장소이거나 브랜치가 없습니다"
3. `GET /repos/{o}/{r}/git/trees/{treeSha}?recursive=1`: 알 수 없는 type(예: tag)이나 path 가 빈 항목은 뺀다

## 테스트 결과

- `./gradlew compileJava`: 성공
- `./gradlew test`: BUILD SUCCESSFUL. GitHubScmClientTest 11, RepoBrowseServiceTest 9, RepoPushServiceTest 5, RepoSyncServiceTest 8, 실패 0
- Jackson 3 역직렬화(`default_branch`, 중첩 branch 응답, tree 항목의 size)와 RepoFileRow 의 null 필드 직렬화는 임시 테스트로 확인한 뒤 그 파일을 지웠다.

## 설계와 달라진 점

- `toRows` 시그니처가 `toRows(List<ScmTreeEntry>, int maxRows)` 이고 결과는 `Rows(rows, truncated)` record 다(상한 테스트를 위해).
- 브랜치 404 문구는 사용자가 브랜치를 지정한 경우에만 쓴다. 빈칸이라 기본 브랜치를 쓴 경우의 404 는 빈 저장소 문구로 보낸다.
- 실패 message 가 null 이면 "저장소 목록을 가져오지 못했습니다." 로 바꾼다.
- (QA 라운드1 MINOR 반영) `branchUri` 는 빈 세그먼트에 더해 `.`·`..` 세그먼트도 ScmException 으로 거부한다. 회귀 단언을 추가했다.
- `owner`·`repo` 는 서비스가 URL 에서 꺼낸다(호스트 다음의 두 세그먼트, `.git` 제거). 꺼낼 수 없으면 null.
