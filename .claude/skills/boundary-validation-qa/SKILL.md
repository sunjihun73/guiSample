---
name: boundary-validation-qa
description: guiSample 의 7대 경계면(REST 응답 jqGrid shape·DTO ↔ JSP/JS·jqGrid colModel, MyBatis ResultMap ↔ dto, 매퍼 HashMap 파라미터 키 ↔ XML #{}, 라우팅 ↔ 호출 URL, 설정(두 프로파일 yaml) ↔ 코드, 소유권 조건, JSP id/class ↔ e2e 셀렉터)과 프로젝트 규약(매퍼 주석 #{}, appDialog, let, common.css, LoginUserSession)을 양쪽 파일을 동시에 읽어 교차 검증하는 방법. 모듈 단위로 점진 실행. integration-qa 가 사용한다. "정합성 검증", "경계면 QA", "shape 비교", "통합 검증", "소유권 누락 점검" 요청 시 트리거.
---

## 사용 시점

integration-qa 가 팀원 산출물을 검증할 때. **모듈 완료 통지마다** 실행한다.

## 핵심 원칙 — 존재 확인은 QA 가 아니다

"메서드가 있다"는 검증이 아니다. 양쪽 파일을 동시에 Read 해 필드명·중첩·타입·조건을 비교한다.

## 7대 경계면

### 1. REST 응답 ↔ JSP/JS
- A: `controller/user/*RestController` 반환값 — 목록은 `Map` 의 `page/total/records/rows`, `rows` 원소 DTO(`dto/`), 단건은 DTO
- B: JSP 의 jqGrid `colModel.name`·`jsonReader`, `$.ajax` success 파서, fetch/SSE 파서
- 확인: `colModel.name` 과 JS 가 읽는 모든 필드가 DTO 필드(camelCase)에 있는가, 목록 키가 jqGrid 기본 키와 같은가, `List` ↔ 단일 객체 혼동
- SSE: 서버가 실패를 정상 종료로 바꾸는지(예외 throw 없음)와 화면이 그 텍스트를 표시하는지

### 2. ResultMap ↔ DTO
- `<resultMap type>` 이 `kr.co.jihun.guisample.dto.*` 인가
- 모든 `property` 가 DTO 필드로 존재, 컬럼 타입과 필드 타입 호환

### 3. 매퍼 파라미터 ↔ XML
- 컨트롤러·서비스가 `param.put("키", …)` 하는 키 ↔ XML `#{키}`·`<if test="키 …">` 의 1:1 대조
- 목록과 count 가 같은 `<sql>` 조각을 쓰는가
- 페이징 키 `startRow`/`pageSize` 가 count **뒤에** 들어가는가
- `@Param` 을 쓴 메서드는 XML 이 `#{name.field}` 로 참조하는가

### 4. 라우팅 ↔ 호출 URL
- 클래스 `@RequestMapping` + 메서드 매핑 = 실제 경로 ↔ JSP/JS 호출 URL·HTTP 메서드·경로 변수
- 새 화면이면 `MainController` 라우트, 사이드바 링크, `activeMenu` 키 일치
- 새 경로가 `SecurityConfig.PUBLIC_PATHS` 에 잘못 들어가지 않았는가

### 5. 설정 ↔ 코드
- `application-local.yaml` 과 `application-e2e.yaml` 이 **둘 다** 바뀌었는가(의도된 차이는 chat 모델명뿐)
- 코드의 `@Value`/`@ConfigurationProperties` 키가 yaml 에 존재, 비밀값은 `.env` 키로
- 임베딩 차원·벡터 DDL·인덱스 metric 일치. 임베딩·검색 임계값이 바뀌면 `measurements.md` 추가 여부

### 6. 소유권 조건 (누락 = BLOCKER)
- 새 조회·변경 경로마다 설계 문서 §3 의 유형대로 조건이 실제로 걸리는지 컨트롤러 → 서비스 → XML 끝까지 추적
- 기준값 출처가 `LoginUserSession.require*()` 인가(`SecurityContextHolder`·`OidcUser` 직접 접근 금지)
- 소유자 컬럼 없는 자식 데이터는 상위 확인 후 빈 목록 반환 경로가 있는가
- `boundedElastic`/SSE 안에서 `loginUserSession` 을 호출하지 않는가
- 예외 처리된 경로는 설계 문서·ADR 에 근거가 있는가

### 7. JSP id/class ↔ e2e 셀렉터
- `git diff` 로 바뀐/사라진 id·class 를 뽑아 `e2e/specs/*.spec.ts` 에서 grep
- 쓰이던 셀렉터가 바뀌었는데 스펙이 그대로면 MAJOR(e2e 실패 확실)
- 기본 대화상자(`alert(`/`confirm(`)가 새로 생기면 workflow 스펙의 `afterEach` 감시에 걸린다 → MAJOR

## 규약 점검 (grep 으로 빠르게)

| 점검 | 방법 | 치명도 |
|------|------|--------|
| 매퍼 XML 주석 안 `#{` | `<!-- -->`·`--`·`/* */` 블록 안의 `#{` 탐색 | BLOCKER(`column index out of range`) |
| 새 JS 의 `alert(`/`confirm(` | diff 범위 grep | MAJOR |
| `appDialog.confirm` 결과를 `if` 로 사용 | 콜백 없이 반환값 사용 | BLOCKER(항상 진행) |
| JSP/JS 의 `var ` | diff 범위 grep | MINOR |
| 화면별 CSS 파일·인라인 `style=` | 새 파일·diff | MINOR |
| 새 조각 JSP 의 `pageEncoding="UTF-8"` 누락 | 조각 파일 첫 줄 | MAJOR(한글 깨짐) |
| `<jsp:include` | grep | MAJOR(보안 설정상 차단) |
| DTO 를 `vo/` 등 다른 패키지에 생성 | 경로 | MAJOR |
| `DataSourceConfig.transactionManager` `@Primary` 제거 | diff | BLOCKER |
| `KeycloakUserProvisioningFilter` 빈 등록 | `@Component` 여부 | MAJOR(이중 실행) |
| DDL 을 실제 DB 에 적용하려는 코드·스크립트 | diff | 보고 후 확인 |

## 점진 실행 시점

| 완료 통지 | 검증 |
|-----------|------|
| `data` | 2(ResultMap), 3(XML 쪽), 매퍼 주석 점검, 6(XML 조건) |
| `backend` | 3(param 키), 5, 6(컨트롤러·서비스), `./gradlew compileJava`·`test` |
| `frontend` | 1, 4, 7, 프론트 규약 |

## 보고 형식 — `_workspace/qa_findings.md` 에 append

```markdown
## {YYYY-MM-DD HH:MM} 라운드 N

### [BLOCKER] 경계면 6 — 소유권
- A: controller/user/ItemRestController.java:42 (`param` 에 createUserId 없음)
- B: resources/mapper/ItemMapper.xml:20 (`<if test="createUserId != null">` — 값이 없으면 조건이 빠짐)
- 기대: count 전에 `loginUserSession.requireUserName()` 을 createUserId 로
- 수정 제안: ...
```

## 흔한 함정

| 함정 | 해결 |
|------|------|
| 한쪽만 보고 결론 | 양쪽 Read 후 보고 |
| `<if>` 조건이 있으니 소유권 OK 로 판단 | 값이 안 들어오면 조건이 조용히 빠진다 — 컨트롤러에서 넣는지까지 추적 |
| psql 로 SQL 이 돌았으니 매퍼 OK | 주석 속 `#{}` 는 MyBatis 에서만 터진다 |
| 전체 완성 후 한 번 검증 | 모듈마다 즉시 |
| BLOCKER 를 모아서 통지 | 즉시 통지 |

## 후속 작업

기존 `qa_findings.md` 의 미해결 BLOCKER/MAJOR 를 먼저 회귀 검증한 뒤 새 검증으로 이동.
