# 0006. 워크플로우 기능 아키텍처 — Flowable 엔진, 전용 DB, 화면 구성

- 상태(Status): 승인됨(Accepted)
- 날짜(Date): 2026-09-22

## 배경(Context)

스텝1 → 스텝2 → 스텝3 → 종료로 진행되는 업무 흐름이 필요해졌다. 화면은 단계마다 하나씩이고, 각 화면에서 "완료"하면 다음 단계로 넘어간다.

이 정도는 `task.step` 같은 상태 컬럼 하나로도 만들 수 있다. 그러나 단계가 늘거나 분기·반려·재할당이 생기는 순간 상태 전이 규칙, 이력, 담당자 질의를 전부 앱 코드로 떠안게 된다. 요구사항 자체가 **워크플로우 엔진 사용**이었으므로 엔진을 도입한다.

제약이 하나 있다. 이 앱은 **Spring Boot 4.0.6** 이다. 워크플로우 엔진 대부분이 아직 Boot 3 라인을 기준으로 배포돼 있어 선택지가 좁다.

## 결정(Decision)

**Flowable 8.0.0**(`flowable-spring-boot-starter-process`)을 임베디드로 쓰고, **진행 상태는 전용 DB(`flowable-db`)에, 업무 정보는 앱 DB(pgvector)의 `task_master` 에** 나눠 둔다. 화면은 단계 3개 + 종료 조회 1개다.

## 구성 요소

| 역할 | 파일 |
|---|---|
| 프로세스 정의(BPMN) | `src/main/resources/processes/simple-task.bpmn20.xml` |
| 엔진 DataSource 교체 | `config/FlowableConfig.java` |
| 앱 기본 트랜잭션 매니저(연쇄 변경) | `config/DataSourceConfig.java` |
| 워크플로우 서비스 | `service/WorkflowService.java` |
| 업무 정보 서비스 | `service/TaskMasterService.java` |
| REST | `controller/user/WorkflowRestController.java` |
| 화면 라우팅 | `controller/user/MainController.java` |
| 응답/도메인 DTO | `dto/WorkflowTaskDTO.java`, `dto/TaskMasterDTO.java` |
| 매퍼 | `mapper/TaskMasterMapper.java`, `resources/mapper/TaskMasterMapper.xml` |
| DDL(수동 적용) | `resources/db/task_master_schema.sql` |
| 화면 | `views/user/workflow/step1·step2·step3·ends.jsp` |
| 공통 조각 | `views/common/sidebar.jsp`, `views/common/dialog.jsp` |
| 클라이언트 | `static/js/workflow.js`, `static/js/dialog.js` |
| 회귀 테스트 | `e2e/specs/workflow.spec.ts` |

## 전체 흐름

```
[브라우저] /user/workflow/step1 (JSP)
     │  jqGrid → GET /user/workflow/tasks?step=step1&page&rows
     │  태스크생성 → POST /user/workflow/tasks           { taskName }
     │  완료      → POST /user/workflow/tasks/{flowableTaskId}/complete { step }
     ▼
[앱] WorkflowRestController — LoginUserSession 에서 user_name 을 꺼내 서비스로 넘긴다
     ▼
[앱] WorkflowService
     ├─(생성) TaskMasterService.createTaskMaster()  ──► 앱 DB(pgvector) task_master   ① 먼저
     ├─(생성) runtimeService.startProcessInstanceByKey() ─► 엔진 DB(flowable-db)      ② 나중
     ├─(목록) taskService.createTaskQuery().taskAssignee(user) ─► 엔진 DB
     │        + TaskMasterService.selectTaskNameMap(ids)      ─► 앱 DB (이름 일괄 조회)
     ├─(완료) taskService.complete()                          ─► 엔진 DB
     └─(종료) historyService.createHistoricProcessInstanceQuery().finished() ─► 엔진 DB
```

두 저장소를 잇는 키는 `taskId`(예: `WF-20260922-231632-31C6`) 하나뿐이다. 물리 FK 는 없다 — DB 가 다르므로 걸 수 없다.

### REST 계약

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/user/workflow/tasks?step=&page=&rows=` | 그 단계에 머물러 있는 내 태스크 목록 |
| GET | `/user/workflow/finished?page=&rows=` | 종료된 내 태스크 목록 |
| POST | `/user/workflow/tasks` | 태스크 생성 (`{ taskName }`) |
| POST | `/user/workflow/tasks/{flowableTaskId}/complete` | 현재 단계 완료 (`{ step }`) |

목록 응답은 프로젝트 규약대로 jqGrid shape `{ page, total, records, rows[] }` 를 지킨다. 페이징은 **소유자 조건을 건 뒤 count 를 먼저 세고** 그 다음 `listPage(startRow, pageSize)` 를 호출한다(총건수와 목록이 같은 조건을 봐야 한다).

## 결정 상세

### 1. 엔진 — Flowable 8.0.0

| 후보 | 기각 사유 |
|------|----------|
| Flowable 7.x | Spring Boot 3 라인 전용이다. 8.0.0 이 Boot 4 에 맞춘 첫 릴리스다(`flowable-dependencies-8.0.0.pom` 의 `spring.boot.version = 4.0.2`) |
| 엔진 없이 상태 컬럼 | 단계 추가·분기·이력·재할당이 전부 앱 코드가 된다. 이번 요구의 전제와 어긋난다 |
| Camunda 8 | 엔진이 앱 밖 Zeebe 브로커로 분리된다. 운영 대상 컨테이너가 하나 더 늘어난다(현재: 앱·pgvector·Keycloak·LiteLLM·nginx) |
| Spring StateMachine | 상태 전이는 되지만 태스크 목록·이력·담당자 질의를 직접 만들어야 한다. 엔진을 쓰는 이유가 없어진다 |

임베디드(같은 JVM)를 고른 이유는 REST 로 분리된 엔진을 쓰면 호출마다 네트워크 경계와 인증이 하나 더 생기기 때문이다. 화면 몇 개짜리 흐름에 그 비용은 과하다.

### 2. 엔진 DB 를 앱 DB 와 분리

엔진은 ACT_*/FLW_* **45개 테이블**을 만든다. 이걸 도메인·벡터 스키마와 같은 DB 에 두면 `\dt` 한 번에 앱 테이블이 묻힌다. 운영 환경에도 워크플로우 DB 가 따로 떠 있다(`/Users/sunjihun/DockerFiles/docker-compose.yml` 의 `flowable-db`, `127.0.0.1:5433/flowable_test` — "RAG / Keycloak DB와 완전 분리"). 그 구성에 맞춘다.

`ProcessEngineAutoConfiguration` 은 `@Primary` DataSource(= pgvector)를 잡으므로, `FlowableConfig` 가 전용 `HikariDataSource` + `DataSourceTransactionManager` 를 만들고 **`EngineConfigurationConfigurer` 로 엔진 빌드 직전에 바꿔 끼운다.** 스키마 생성·BPMN 자동 배포 등 나머지는 오토컨피그 기본값을 그대로 쓴다.

여기에 따라오는 **연쇄 변경이 하나 있다.** Flowable 전용 트랜잭션 매니저 빈이 생기면 Boot 의 트랜잭션 매니저 자동구성(`@ConditionalOnMissingBean(TransactionManager.class)`)이 물러난다. 그대로 두면 기존 `@Transactional`(예: `UserMasterService.loadOrRegister`)이 워크플로우 DB 를 잡는다. 그래서 앱 기본 매니저를 `DataSourceConfig.transactionManager()` 에 `@Primary` 로 명시했다.

### 3. 스키마 관리 — 엔진 쪽만 자동

이 프로젝트는 `db/*.sql` 을 **수동 적용**하는 규칙이지만(ADR 0001·0005), ACT_* 는 예외로 `flowable.database-schema-update: true` 를 쓴다. 엔진 버전과 스키마가 한 몸이라 손으로 관리할 이유가 없고, DDL 이 45개 테이블 분량이라 사람이 따라갈 수 없다. **분리된 DB 라 이 예외가 앱 스키마에 영향을 주지 않는다**는 점이 전제다.

앱 DB 쪽 `task_master` 는 규칙대로 수동 적용(`db/task_master_schema.sql`)이다.

### 4. 프로세스 정의 — userTask 세 개의 순차 흐름

`resources/processes/simple-task.bpmn20.xml` 하나. `start → step1 → step2 → step3 → end`.

- userTask id(`step1`/`step2`/`step3`)가 **REST 의 `step` 파라미터·화면 파일명과 1:1** 이다. 세 이름을 일부러 같게 두어 화면 ↔ 엔진 대응을 추적 가능하게 했다.
- 서비스 태스크·타이머·비동기 분기가 없으므로 잡이 생기지 않는다. `async-executor-activate: false` 로 잡 실행기 스레드를 띄우지 않는다.
- 게이트웨이·반려가 없는 직선 흐름이라 BPMN 다이어그램 정보(`BPMNDiagram`)는 넣지 않았다. 흐름이 복잡해지면 그때 추가한다.

### 5. 업무 정보는 앱 DB(`task_master`)에 둔다

엔진은 "이 태스크가 어느 단계에 있는가"만 안다. 태스크 이름 같은 업무 정보는 엔진의 관심사가 아니므로 앱 DB 에 `task_master`(`task_id`, `task_name`, `task_owner_user_id`)를 두고, `task_id` 로 두 저장소를 잇는다. 같은 값이 엔진 쪽 business key 와 프로세스 변수 `taskNo` 에도 들어간다.

엔진 변수를 이름 저장소로 쓰지 않은 이유는, 프로세스 변수는 인스턴스가 끝나면 이력 테이블로 넘어가고 조회 조건으로 쓰기 불편하기 때문이다. 업무 데이터는 업무 DB 에 두는 편이 나중에 목록·검색·통계를 붙일 때 자연스럽다.

**쓰는 순서가 계약이다.** `task_master` INSERT 를 앱 트랜잭션 안에서 먼저 하고 그 다음 프로세스를 시작한다. 엔진 시작이 실패하면 앱 트랜잭션이 함께 롤백돼 양쪽 모두 남지 않는다. 반대 순서라면 이름 없는 태스크가 엔진에 남는다. 앱 트랜잭션 커밋 자체가 실패하는 좁은 구간은 막지 못하므로, **읽는 쪽은 이름이 없어도 목록이 깨지지 않게** 만들었다(이름 칸만 빈다).

이름 조회는 **화면에 보이는 건만 한 번에** 읽는다(`selectTaskNameMap(taskIds, userName)`). 행마다 조회하면 페이지당 N+1 번의 크로스 DB 왕복이 된다.

### 6. id 가 둘이다 — `taskId` 와 `flowableTaskId`

- **`taskId`** — `WF-20260922-231632-31C6`. 사람이 읽는 업무 식별자이고 `task_master.task_id` 이자 엔진 business key 다. 화면 표시와 두 DB 연결에 쓴다.
- **`flowableTaskId`** — 엔진이 **단계마다 새로 발급**하는 내부 태스크 id(UUID). 완료 요청의 대상이며 목록 행의 rowId 다.

처음에는 둘 다 "taskId" 로 불렀는데, 완료 대상과 업무 식별자가 같은 이름으로 섞여 위험했다. 응답 필드 이름을 갈라 두면 화면·서버 어느 쪽에서도 혼동이 생기지 않는다.

**엔진 프로세스 변수만 예외로 `taskNo` 라는 옛 이름을 유지한다.** 이미 흐르고 있는 인스턴스의 변수명을 바꾸면 그 건들의 값이 사라지기 때문이다. 이름 변환은 `WorkflowService.toDto()` 한 곳에서만 일어난다.

### 7. 소유권 — 앱 필터가 아니라 엔진의 assignee

이 프로젝트의 소유권 규칙은 `user_master.user_name` 을 모든 조회에 거는 것이다(CLAUDE.md). 워크플로우에서는 **엔진 기능으로 같은 규칙을 만든다.**

- 프로세스 시작 시 `initiator` 변수에 `user_name` 을 넣고, 세 userTask 가 `flowable:assignee="${initiator}"` 로 받는다.
- 목록·완료·이력 조회가 전부 `taskAssignee(userName)` / `variableValueEquals("initiator", userName)` 로 걸린다. **조건이 빠지면 결과가 비는 쪽으로 실패**하지, 남의 태스크가 새는 쪽으로 실패하지 않는다.
- `task_master` 조회에도 `task_owner_user_id` 조건을 함께 건다. 엔진 쪽 필터와 어긋나더라도 남의 이름이 섞이지 않는다.
- 기준값은 다른 도메인과 같이 **컨트롤러가 `LoginUserSession` 에서 꺼내** 서비스로 넘긴다. 서비스·엔진은 세션을 모른다.

엔진 쪽에 별도 소유자 컬럼을 만들지 않은 이유는, 엔진이 이미 assignee 로 인덱싱된 질의를 제공하는데 그 옆에 같은 뜻의 컬럼을 하나 더 두면 둘이 어긋날 수 있기 때문이다.

### 8. 완료 요청은 단계를 함께 보낸다

`POST /user/workflow/tasks/{flowableTaskId}/complete` 는 본문에 **요청을 보낸 화면의 `step`** 을 함께 받는다. 태스크의 실제 단계와 다르면 거부한다.

목록을 열어 둔 채 다른 탭에서 진행시키면 화면의 버튼은 이미 넘어간 태스크를 가리킨다. 그대로 완료시키면 사용자가 스텝1 화면에서 누른 버튼이 스텝2 를 완료시키는 일이 생긴다. 단계를 함께 검사하면 그 경우가 "목록을 새로고침하라"는 메시지로 끝난다.

### 9. 화면 구성 — 단계 3개 + 종료 1개

| 화면 | 경로 | 성격 |
|---|---|---|
| 스텝1 | `/user/workflow/step1` | 목록 + **태스크이름 입력·생성** + 완료 |
| 스텝2 / 스텝3 | `/user/workflow/step2·step3` | 목록 + 완료 |
| 종료 | `/user/workflow/ends` | 종료된 건 조회 **(읽기 전용)** |

**종료는 BPMN 의 단계가 아니다.** endEvent 는 태스크를 남기지 않으므로 조회 대상이 `TaskService` 가 아니라 `HistoryService`(끝난 프로세스 인스턴스)다. 그래서 `STEP_KEYS` 에 넣지 않고 별도 엔드포인트(`/finished`)를 둔다. 화면상으로는 좌측 메뉴 4번째로 스텝 화면들과 같은 자리에 있지만 완료 버튼이 없다.

(초기에는 종료 목록을 스텝3 화면 하단에 붙였다가, 전용 화면이 생기면서 같은 데이터가 두 곳에 나오지 않도록 옮겼다.)

네 화면이 "목록 + 완료" 라는 같은 구조라서 jqGrid 구성과 완료 처리는 `static/js/workflow.js` 한 곳에 두고 화면은 단계 키만 넘긴다.

### 10. 공통 조각 — 사이드바와 대화상자

메뉴가 4개 화면으로 늘면서 좌측 사이드바를 `views/common/sidebar.jsp` 한 곳으로 합쳤다(기존 화면 4개도 이 조각을 쓴다). 각 화면은 include 앞에 `String activeMenu` 를 선언한다.

알림·확인은 브라우저 기본 `alert()`/`confirm()` 대신 **`appDialog`**(마크업 `views/common/dialog.jsp`, 동작 `static/js/dialog.js`)를 쓴다. 기본 대화상자는 화면 스타일과 따로 놀고, 메시지가 길어지면 읽기 어렵다.

두 조각 모두 **정적 include(`<%@ include %>`)** 다. 런타임 `<jsp:include>` 는 `DispatcherType.INCLUDE` 디스패치를 일으키는데 `SecurityConfig` 는 FORWARD/ERROR 만 permitAll 로 열어 두었다. 정적 include 는 컴파일 시점에 본문이 합쳐져 디스패치가 없으므로 보안 설정을 건드리지 않는다.

## 실측 근거

배포·동작은 실제 기동과 브라우저로 확인했다.

```
flowable_test(5433): ACT_*/FLW_* 45 tables
act_re_procdef : simpleTaskProcess | 단순 3단계 워크플로우 | v1

태스크 생성 → WF-20260922-233147-7DAF (이름: "결재 요청 …")
스텝1 완료 → {success:true, nextStep:"스텝2", finished:false}
스텝2 완료 → {success:true, nextStep:"스텝3", finished:false}
스텝3 완료 → {success:true, nextStep:"종료",  finished:true}  → 종료 화면(ends)에서 조회됨

두 DB 에 같은 값이 남는지 대조:
  pgvector(5432) task_master.task_id           = WF-20260922-233147-7DAF
  flowable(5433) ACT_HI_PROCINST.business_key_ = WF-20260922-233147-7DAF
```

회귀는 `e2e/specs/workflow.spec.ts`(10건)가 잡는다 — 메뉴 구성, 화면별 버튼 유무, 생성·이름 전파·수명주기, 단계 어긋난 완료 거부, 경고/확인 팝업, jqGrid 응답 shape. 전체 스위트 31건 통과.

## 결과(Consequences)

### 좋은 점

- 단계 추가·분기·타이머가 BPMN 수정으로 끝난다. 진행 중 인스턴스는 기존 버전으로 계속 흐른다(엔진이 정의를 버전별로 보관).
- 이력이 공짜로 남는다. 종료 목록은 `HistoryService` 질의 한 번이고, 앱이 별도 이력 테이블을 만들지 않았다.
- 소유권이 엔진 질의 조건이라 앱에서 필터를 빠뜨릴 경로가 없다.
- ACT_* 45개가 앱 DB 에 섞이지 않는다.
- 업무 정보가 앱 DB 에 있어 목록·검색·통계를 붙일 때 엔진 API 를 거치지 않아도 된다.

### 감수하는 것

- **도메인 데이터와 워크플로우 상태가 한 트랜잭션에 묶이지 않는다.** DataSource 가 둘이고 2PC 를 쓰지 않는다. 생성 경로는 "앱 먼저, 엔진 나중" 순서로 대부분의 실패를 막았지만, 둘을 함께 바꾸는 기능이 더 생기면 **순서와 보상을 그때마다 명시**해야 한다.
- **DB 가 하나 더 떠 있어야 한다.** `flowable-db` 가 내려가면 워크플로우 화면만 죽는 게 아니라 **엔진 빌드가 실패해 앱 기동 자체가 막힌다**(초기화 시점에 커넥션을 잡는다).
- **`@Primary` 트랜잭션 매니저 선언이 필수가 됐다.** `DataSourceConfig.transactionManager()` 를 지우면 기존 `@Transactional` 이 조용히 워크플로우 DB 로 넘어간다. 눈에 띄지 않는 종류의 고장이다.
- **BPMN 의 userTask id 가 화면·REST 와 결합돼 있다.** 바꾸려면 `WorkflowService.STEP_KEYS` 와 스텝 화면 3개를 함께 고쳐야 한다. 느슨하게 만들 수도 있었지만, 매핑 테이블을 하나 더 두는 쪽이 오히려 추적을 어렵게 한다고 판단했다.
- **태스크 이름이 없는 건이 생길 수 있다.** 이 기능 이전에 만들어진 태스크, 그리고 앱 트랜잭션 커밋이 실패한 건은 `task_master` 에 행이 없다. 목록은 이름 칸을 비우고 정상 동작하지만, 이름을 필수로 다루려면 백필이 필요하다.
- **`taskId` 에 유니크 제약이 없다.** 형식이 `WF-<초 단위 시각>-<UUID 앞 4자리>` 라 같은 초에 두 건이 생기면 뒤 4자리로만 갈린다(`task_master` PK 가 사실상의 방어선이다). 업무 키로 승격시킬 거라면 형식과 제약을 다시 정할 것.
- **`appDialog` 는 기본 `confirm()` 과 달리 스크립트를 멈추지 않는다.** `if (confirm(...))` 형태를 그대로 옮겨 적으면 확인을 누르지 않아도 진행된다. 결과는 반드시 `onConfirm`/`onCancel` 콜백으로 받아야 한다.
- **Keycloak 사용자와 엔진의 IDM 이 분리돼 있다.** assignee 는 `user_name` 문자열일 뿐이고 ACT_ID_* 는 비어 있다. 그룹·후보자(candidate group) 할당이 필요해지면 그때 동기화를 결정해야 한다.
- 엔진이 잡는 커넥션이 별도 풀(`flowable.datasource.maximum-pool-size: 8`)로 늘어난다.

## 운영 주의

- `db/task_master_schema.sql` 은 **수동 적용**이며 대상은 **앱 DB(5432)** 다. 미적용 시 태스크 생성이 INSERT 에서 실패한다(엔진은 시작되지 않으므로 반쪽 데이터는 남지 않는다).
- `.env` 에 **`FLOWABLE_DB_PASSWORD`** 가 있어야 기동한다. 없으면 플레이스홀더 해석 실패로 뜨지 않는다.
- 컨테이너를 새로 만들면 비밀번호는 compose 기본값(`FLOWABLE_DB_PASSWORD:-fwloable`)을 따르지만, **기존 볼륨(`flowable-pgdata`)을 재사용하면 볼륨 생성 당시의 비밀번호가 유지된다.** 컨테이너 안에서는 `127.0.0.1` 이 `trust` 라 psql 로는 확인되지 않으니, 외부 접속(5433)으로 검증할 것.
- BPMN 파일을 고치면 기동 시 **새 버전으로 재배포**된다. 진행 중 인스턴스는 옛 버전으로 계속 흐르므로, 단계 키를 바꾼 직후에는 옛 버전 태스크가 화면에서 사라져 보일 수 있다.
- 공통 조각(`sidebar.jsp`, `dialog.jsp`)에는 **`<%@ page pageEncoding="UTF-8" %>` 가 반드시 있어야 한다.** 정적 include 는 포함되는 파일의 인코딩을 따로 판단해 기본값(ISO-8859-1)으로 읽으므로, 없으면 한글이 깨진다.
- e2e 는 기본 대화상자가 아니라 `#appDialog` 팝업의 버튼을 클릭해 진행한다. 화면을 기본 `alert()` 로 되돌리면 스펙이 깨진다(그리고 `afterEach` 가 기본 대화상자 출현을 실패로 잡는다).
- 목록 그리드의 페이지 크기는 10 이다. 태스크가 쌓이면 새로 만든 건은 **1페이지 맨 위**에 온다(생성 최신순).

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-09-22 | 최초 작성 — Flowable 8 채택, 전용 DB 분리, BPMN·소유권·완료 가드 |
| 2026-09-22 | 종료 조회 화면(`ends.jsp`) 추가, 종료 목록을 스텝3 하단에서 이관 |
| 2026-09-22 | `task_master`(업무 정보) 도입, 쓰기 순서 계약, `taskId`/`flowableTaskId` 이름 분리 |
| 2026-09-22 | 알림·확인을 공통 레이어 팝업(`appDialog`)으로 교체 |

## 관련 문서

- [0001. pgvector 채택](0001-use-pgvector.md) — 단일 DataSource 전제. 이 문서가 그 전제를 워크플로우에 한해 깬다
- [0003. Keycloak SSO·세션 관리](0003-keycloak.md) — `LoginUserSession` 과 소유권 기준값의 출처
- [0005. 하이브리드 검색·RAG 소유권](0005-hybrid-bm25-rrf.md) — 소유권 규칙의 선례
