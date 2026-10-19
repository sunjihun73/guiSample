---
name: feature-orchestrator
description: guiSample 기능 개발 5인 팀(feature-architect, spring-backend-engineer, mybatis-data-engineer, jsp-frontend-engineer, integration-qa)을 실행·조율하는 오케스트레이터. 백엔드·DB·JSP 화면 중 2개 이상 계층에 걸친 기능 추가·수정에 사용한다 — RAG/Spring AI(벡터·하이브리드 검색, 문서 인덱싱, 챗봇), Flowable 워크플로우, 프로젝트 관리·REPO동기화/PUSH(ScmClient), 카테고리·지식파일 등 도메인 화면. "RAG 기능 추가", "검색 개선", "워크플로우 단계 추가", "프로젝트 화면에 기능 추가", "저장소 연동 추가(GitLab/SVN)", "새 화면 만들어줘", "다시 만들어", "재실행", "업데이트", "수정", "보완", "화면만 다시", "이전 결과 기반 개선" 등 신규·후속 요청 시 반드시 트리거. 설계 조언·사용법 질문이나 한 파일 1~2줄 수정은 직접 처리하고, e2e 실행만 하는 요청이나 하네스 자체 점검은 이 스킬이 아니다.
---

## 사용 시점

다음 중 하나라도 해당하면 이 스킬로 진입한다. 그 외(단순 질문, 한 파일의 작은 수정)는 직접 처리한다.

- 새 기능 추가 (RAG·워크플로우·프로젝트·SCM·기타 화면)
- 기존 기능 변경이 백엔드 / DB(매퍼·DDL) / JSP 화면 중 2개 이상 계층에 걸침
- 사용자가 "다시", "재실행", "보완", "수정" 등 후속 작업 표현을 사용

진입 전에 `CLAUDE.md` 에서 해당 도메인의 섹션과, 그 섹션이 가리키는 ADR(`src/docs/adr/`)을 읽는다. 인증·RAG·워크플로우·REPO동기화/PUSH 흐름을 바꾸는 작업이면 ADR 확인은 필수다.

## 실행 모드

**혼합 모드** — 단계마다 성격이 다르다.

| Phase | 실행 모드 | 이유 |
|-------|----------|------|
| 1 설계 | 서브에이전트 위임 (`feature-architect` 1회) | 설계 문서 하나만 받으면 되고 대화가 필요 없다 |
| 2 구현 | 지속형 에이전트 협업 (`Agent(name:)` + `SendMessage`) | 매퍼 시그니처·응답 shape 를 계층끼리 협상해야 한다 |
| 3 검증 | 메인이 직접 실행 + 남은 지적은 지속형 에이전트에 `SendMessage` | 빌드·테스트는 결정적 명령이라 위임할 필요가 없다 |

`TeamCreate`/`TeamDelete` 는 쓰지 않는다(없는 도구다). 이름을 붙여 실행한 에이전트는 자동으로 같은 협업 그룹이 되고, 끝난 뒤에도 `SendMessage({to: "<name>"})` 로 맥락을 유지한 채 다시 지시할 수 있다.

## Phase 0: 컨텍스트 확인 — 반드시 가장 먼저 실행

```
ls _workspace/ 2>/dev/null && head -20 _workspace/00_architect_design.md
```

| 상황 | 동작 |
|------|------|
| `_workspace/` 없음 | **초기 실행** — Phase 1부터 |
| 있음 + 같은 기능에 대한 수정·보완 요청 | **부분 재실행** — 영향받는 에이전트만 실행. `_workspace` 보존 |
| 있음 + 다른 기능의 새 요청 | **새 실행** — `mv _workspace _workspace_prev_$(date +%F)` 후 Phase 1부터 |

`00_architect_design.md` 제목으로 같은 기능인지 판단한다. 판단이 안 되면 사용자에게 묻는다.

## Phase 1: 설계 — 서브에이전트

```
Agent(
  description: "기능 설계",
  subagent_type: "feature-architect",
  prompt: """
    스킬 feature-design 을 사용해 _workspace/00_architect_design.md 를 작성하라.
    사용자 요구사항: {요구사항}
    관련 ADR: {CLAUDE.md 에서 찾은 ADR 번호}
    부분 재실행이면: 기존 설계 문서를 읽고 {피드백 대상 섹션}만 갱신하라.
  """
)
```

완료 후 설계 요약(범위, API, 스키마 변경, 영향받는 화면, 소유권 처리 방식)을 사용자에게 보고하고 **확인받기 전에는 Phase 2로 넘어가지 않는다.** 스키마 변경(DDL)이 있으면 적용 대상 DB(앱 5432 / 워크플로우 5433)를 보고에 명시한다.

## Phase 2: 구현 — 지속형 에이전트

설계 문서 §8 분배 지시에서 필요한 계층만 실행한다. **메시지 한 번에 병렬로** 실행한다.

```
Agent(name: "data",     subagent_type: "mybatis-data-engineer",   prompt: "...")
Agent(name: "backend",  subagent_type: "spring-backend-engineer", prompt: "...")
Agent(name: "frontend", subagent_type: "jsp-frontend-engineer",   prompt: "...")
Agent(name: "qa",       subagent_type: "integration-qa",          prompt: "...")
```

각 prompt 에는 다음을 넣는다.
- 설계 문서 경로와 담당 섹션 번호
- 협업 상대의 **이름**(`data`/`backend`/`frontend`/`qa`) — `SendMessage` 의 `to` 로 쓴다
- "첫 응답에서 실제로 쓸 수 있는 도구 목록을 보고하라" — 정의 파일의 `tools` 가 실제로 주어지지 않는 사례가 있다. Edit/Write 가 빠졌으면 즉시 같은 유형으로 다시 실행한다

### 의존 관계

```
data     — DDL + Mapper 시그니처 확정 → backend 에 SendMessage
backend  — DTO/Config 먼저, Mapper 주입은 시그니처 확정 후 → 응답 shape 를 frontend 에 SendMessage
frontend — 응답 shape 수신 후 JS 파서 작성 (JSP 마크업은 먼저 시작 가능)
qa       — 각자의 완료 SendMessage 를 받을 때마다 해당 경계면 검증 (점진 QA)
```

`TaskCreate`/`TaskUpdate` 가 실행 환경에 있으면 위 4개 작업을 등록하고 `blockedBy` 로 의존을 건다. 없으면 `SendMessage` + 아래 요약 파일로만 조율해도 충분하다.

### 데이터 전달

| 방법 | 내용 |
|------|------|
| `SendMessage` | 매퍼 시그니처, REST 응답 shape, QA BLOCKER |
| 파일 | `_workspace/01_backend_summary.md`, `02_data_summary.md`, `03_frontend_summary.md`, `qa_findings.md`(append) |

화면이 없는 작업(예: `ScmClient` 구현체 추가)이면 `frontend` 를 실행하지 않는다. 화면만 고치는 작업이면 `frontend` + `qa` 만 실행한다.

## Phase 3: 통합 검증 — 메인 직접

1. `qa` 가 남긴 `_workspace/qa_findings.md` 에 미해결 BLOCKER/MAJOR 가 있으면 해당 에이전트에 `SendMessage` 로 수정 지시
2. `./gradlew compileJava` — 컴파일
3. `./gradlew test` — 순수 단위 테스트(REPO동기화 매칭·GitHub URL 파싱 등). 새 순수 로직을 추가했으면 테스트도 추가됐는지 확인
4. **매퍼 XML 을 바꿨으면** MyBatis 를 실제로 통과시키는 확인이 필요하다(psql 직접 실행으로는 주석 속 `#{}` 문제를 못 잡는다). 앱 기동 후 해당 화면/API 를 한 번 호출하거나 e2e 로 확인
5. **JSP 의 id/class 를 바꿨거나 화면 흐름을 바꿨으면** e2e 실행: `cd e2e && npm test`. 인프라(pgvector·Keycloak·LiteLLM·nginx·flowable-db)가 없으면 `preflight` 가 중단 사유를 출력한다 — 이 경우 실행하지 못했다고 보고하고 성공으로 적지 않는다
6. 새 DDL 은 `src/main/resources/db/*.sql` 에 파일로만 두고 **적용하지 않는다**(수동 적용 대상). 적용 대상 DB 와 순서를 보고에 적는다
7. 기능·API 가 바뀌었으면 `README.md`, 흐름이 바뀌었으면 해당 ADR·`CLAUDE.md` 갱신 필요 여부를 보고한다
8. 변경 파일 목록, 엔드포인트·화면 요약, 실행한/못 한 검증을 사용자에게 보고

## Phase 4: 피드백 수집

> "결과에서 개선할 부분이 있나요? 팀 구성이나 진행 방식에 바꾸고 싶은 점이 있으면 알려주세요."

같은 종류의 피드백이 두 번 이상 나오면 `harness:evolve` 로 하네스에 반영하자고 제안한다.

## 오류 처리

| 오류 | 대응 |
|------|------|
| 에이전트 작업 실패 | 1회 재시도. 재실패 시 그 결과 없이 진행하고 보고서에 누락 명시 |
| 사용량 한도·인증 만료·권한 거부 | 재시도하지 않는다. 부분 산출물을 직접 열어 진행 범위를 확인하고, 누락을 `_workspace/` 에 기록해 보고(한도면 해제 시각 포함) |
| 에이전트 무응답 | `SendMessage` 로 상태 확인 후 재지시. 그래도 실패하면 같은 유형을 새 이름으로 실행하고 요약 파일 경로를 넘긴다 |
| 컴파일 실패 | `backend`(또는 원인 계층)에 수정 지시. 3회 실패 시 사용자에게 보고 |
| DDL 적용 필요 | 파일만 작성. 실제 DB 적용은 사용자 명시 승인 후 |
| 계층 간 시그니처 협의 결렬 | 메인이 설계 문서 기준으로 결정. 설계 자체가 틀렸으면 `feature-architect` 를 부분 재실행 |
| 멈춘 에이전트의 빈자리 | 메인은 직접 확인한 사실만 채운다. 에이전트의 판단·근거를 추측해 쓰지 않는다 |

## 규모

| 작업 | 구성 |
|------|------|
| 소: API 하나 + 매퍼 | architect → data + backend + qa |
| 중: API + 화면 | 5명 전체 |
| 대: 여러 화면·도메인 | Phase 2 를 2A(data+backend) → 2B(frontend+qa) 로 나눠 실행 |

## 테스트 시나리오

### 정상 흐름
사용자: "프로젝트 목록에 담당자 컬럼을 추가하고 검색 조건에도 넣어줘"
1. Phase 0: `_workspace/` 의 설계가 다른 기능 → `_workspace_prev_*` 로 이동, 새 실행
2. Phase 1: `feature-architect` 가 DDL(`project_master` 컬럼 추가), 검색 조건, jqGrid 컬럼, 소유권 영향(없음) 설계 → 사용자 확인
3. Phase 2: data(DDL 파일 + `ProjectMasterMapper.xml` `<sql>` 조건) / backend(DTO 필드, count 전에 조건 넣기) / frontend(`projects.jsp` colModel·검색폼, id 유지) / qa(ResultMap↔DTO, jqGrid shape↔colModel, e2e 셀렉터 영향)
4. Phase 3: compileJava·test 통과, 매퍼 변경이므로 화면 호출로 MyBatis 통과 확인, DDL 은 5432 에 수동 적용하라고 보고
5. Phase 4: 피드백 요청

### 부분 재실행 / 오류 흐름
사용자: "워크플로우 스텝2 화면만 다시 만들어줘"
1. Phase 0: 같은 기능 설계 존재 → 부분 재실행, Phase 1 생략
2. Phase 2: `frontend` + `qa` 만 실행. `frontend` 의 첫 보고에 Edit 도구가 없음 → 같은 유형을 새 이름으로 다시 실행
3. qa 가 `#appDialog` 대신 `confirm()` 사용을 BLOCKER 로 보고 → frontend 수정
4. Phase 3: e2e 인프라 미기동으로 `preflight` 중단 → "workflow 스펙 미실행" 으로 보고(성공으로 적지 않음)
