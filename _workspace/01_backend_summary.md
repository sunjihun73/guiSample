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

---

# 후속 1 — 끌어오기 결과를 브라우저 PC 폴더에 저장 (2026-10-19, 설계 §13)

## 파일

| 구분 | 경로 |
|---|---|
| 신규 | `src/main/java/kr/co/jihun/guisample/scm/ScmArchiveVisitor.java` |
| 신규 | `src/main/java/kr/co/jihun/guisample/scm/ScmPaths.java` |
| 수정 | `src/main/java/kr/co/jihun/guisample/scm/ScmClient.java` (`default readArchive` 추가, javadoc) |
| 수정 | `src/main/java/kr/co/jihun/guisample/scm/github/GitHubScmClient.java` |
| 신규 | `src/main/java/kr/co/jihun/guisample/service/RepoArchiveFrameWriter.java` |
| 신규 | `src/main/java/kr/co/jihun/guisample/service/RepoArchiveService.java` |
| 수정 | `src/main/java/kr/co/jihun/guisample/controller/user/RepositoryRestController.java` (`GET /archive` 추가, `files` 불변) |
| 신규 테스트 | `src/test/java/kr/co/jihun/guisample/scm/ScmPathsTest.java`, `service/RepoArchiveFrameWriterTest.java`, `service/RepoArchiveServiceTest.java` |
| 수정 테스트 | `src/test/java/kr/co/jihun/guisample/scm/github/GitHubScmClientTest.java` (7건 추가) |

yaml 두 파일·`build.gradle`·DB·매퍼 변경 없음. 기존 가짜 `ScmClient`(`RepoSyncServiceTest`·`RepoPushServiceTest`·`RepoBrowseServiceTest`)는 수정 없이 컴파일된다.

## 엔드포인트

| 경로 | 메서드 | 응답 | 소유권 |
|---|---|---|---|
| `/user/repository/archive?repoUrl=&ref=` | GET | **항상 200** `application/octet-stream` 프레임 스트림, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition` 없음 | 해당 없음 (앱 DB 미사용, `LoginUserSession` 미사용). 인증은 `/user/**` (미인증 AJAX 401) |

프레임 형식은 설계 §13.5 그대로(빅엔디언): `MAGIC = 47 53 52 41 01` 다음 `'F' str(path) u32(n) bytes[n]` / `'S' str(path) str(reason)` / `'E' str(message)`(마지막) / `'Z' u32 fileCount u32 skippedCount u64 totalBytes`(마지막), `str = u32(byteLen) + UTF-8`. 검증 실패(repoUrl·ref 공백, 미지원 URL)도 MAGIC 다음 `'E'`. 레코드마다 flush. `'S'` 는 `'F'` 사이에 섞여 올 수 있다. 컨트롤러는 어떤 예외도 밖으로 던지지 않는다(오류 프레임 쓰기마저 실패하면 로그만).

오류 메시지(`'E'`): `저장소 URL 을 입력하세요` / `커밋 ID 가 없습니다. 먼저 목록을 끌어오세요` / `지원하지 않는 저장소 URL 입니다: …` / `저장소 또는 커밋을 찾을 수 없음 (비공개 저장소면 토큰 필요): …` / 401·403·429 는 기존 `toScmException` 문구 / `저장소 압축본이 상한(50 MiB)을 넘어 내려받을 수 없습니다: …` / `파일 크기가 상한(100 MiB)을 넘습니다: {path}` / `파일 수가 상한(20000개)을 넘어 중단했습니다` / `저장소 내용 합계가 상한(500 MiB)을 넘어 중단했습니다` / `GitHub 호출 실패: … (…)`(타임아웃 등). 건너뜀 사유(`'S'`): `허용되지 않는 경로`, `아카이브 최상위 폴더 밖의 항목`.

## 시그니처

```java
// scm/ScmClient
default void readArchive(String repoUrl, String ref, ScmArchiveVisitor visitor) throws IOException   // 기본: ScmException("이 저장소는 파일 내려받기를 지원하지 않습니다: …")
// scm/ScmArchiveVisitor
void onFile(String path, InputStream content) throws IOException;
default void onSkipped(String path, String reason) throws IOException { }
// scm/ScmPaths
public static String safeRelativePath(String path)
// scm/github/GitHubScmClient (package-private static)
static final long MAX_ARCHIVE_BYTES = 50 MiB;
static URI zipballUri(UriBuilder uri, RepoRef ref, String refValue)
static void readZip(InputStream zip, ScmArchiveVisitor visitor) throws IOException
static String stripArchiveRoot(String entryName, String root)
// service/RepoArchiveService
public static final int MAX_FILES = RepoBrowseService.MAX_ROWS; MAX_FILE_BYTES = 100 MiB; MAX_TOTAL_BYTES = 500 MiB;
public Result streamArchive(String repoUrl, String ref, RepoArchiveFrameWriter writer) throws IOException
static byte[] readLimited(InputStream in, long max) throws IOException
public record Result(int fileCount, int skippedCount, long totalBytes)
// service/RepoArchiveFrameWriter
public RepoArchiveFrameWriter(OutputStream out); start(); writeFile(String, byte[]); writeSkipped(String, String); writeEnd(int, int, long); writeError(String)
// controller/user/RepositoryRestController
@GetMapping("/archive") public void archive(@RequestParam(required=false) String repoUrl, @RequestParam(required=false) String ref, HttpServletResponse response)
```

## GitHub 호출 (readArchive)

- `GET /repos/{o}/{r}/zipball/{ref 세그먼트…}` 1회(+ codeload 302 를 JDK HttpClient 가 자동 추적). 아카이브 전용 `archiveClient`(readTimeout 50초). 기존 `restClient`(20초)는 같은 빌더(`buildClient`)로 만들 뿐 설정 불변.
- `exchange` 안에서 상태 ≥ 400 이면 `toScmException`(상태 기준 오버로드 신설, 404 문구만 "저장소 또는 커밋을 찾을 수 없음 …"), Content-Length > 50 MiB 면 즉시 거부, 본문은 `readNBytes(50 MiB + 1)` 로 받아 초과 시 거부. 연결을 닫은 뒤 `ByteArrayInputStream` 으로 `readZip`. 서버 디스크 미사용.
- `readZip`: 첫 항목의 첫 세그먼트를 최상위 폴더로 고정(첫 항목에 '/' 가 없으면 최상위 없음 → 파일 전부 건너뜀), 디렉터리 항목 무시, 다른 최상위·안전하지 않은 경로는 `onSkipped`. visitor 에 넘기는 스트림은 close 무시 래퍼.
- `branchUri` 와 `zipballUri` 는 private `refUri` 로 세그먼트 검사를 공유한다(동작·문구 불변, 기존 회귀 테스트 통과).

## 테스트 결과

- `./gradlew compileJava`: 성공
- `./gradlew test`: BUILD SUCCESSFUL. ScmPathsTest 2, GitHubScmClientTest 18(기존 11 + 7), RepoArchiveFrameWriterTest 4, RepoArchiveServiceTest 6, RepoBrowseServiceTest 9, RepoPushServiceTest 5, RepoSyncServiceTest 8 — 총 52, 실패 0
- GitHubScmClientTest 의 `zipball_을_리다이렉트를_따라_받아_풀고_404_는…` 는 JDK `HttpServer` 루프백(127.0.0.1, 외부 네트워크 없음)으로 302 추적·404 문구·`exchange` 안에서 던진 `ScmException` 이 RestClient 에 감싸이지 않고 나오는지 확인한다.
- **실제 호출 확인(분배 지시 9, 앱 미기동 — 세션 쿠키 없이 같은 조합을 scratchpad 의 main 으로 직접 실행)**: `GitHubScmClient`(토큰 없음) + `RepoArchiveService` + `RepoArchiveFrameWriter` 로 `https://github.com/sunjihun73/guiSample.git` / `main` → 1.07초, 프레임 7,789,455 바이트, 머리 `47 53 52 41 01`, `F` 380개, `S` 0, 끝 `Z files=380 skipped=0 total=7764256`, `build.gradle` 2037 바이트. 없는 ref(`no-such-branch-xyz`) → MAGIC + `E 저장소 또는 커밋을 찾을 수 없음 (비공개 저장소면 토큰 필요): …`. 앱을 거친 curl 확인(인증·nginx 경유)은 QA/Phase 3 몫으로 남긴다.

## 설계와 달라진 점

- `ScmArchiveVisitor.onSkipped` 에 `throws IOException` 을 붙였다 — 서비스의 visitor 가 그 안에서 `'S'` 프레임을 쓰기 때문(기본 구현은 그대로 빈 본문).
- GitHub 클라이언트는 `RepoArchiveService.readLimited` 를 부르지 않고 같은 방식(`readNBytes(max+1)`)을 직접 쓴다 — `scm` 패키지가 `service` 에 의존하지 않게 하기 위해서다.
- 파일 크기 초과 메시지에 경로를 붙인다(`…: {path}`).
