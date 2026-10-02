---
name: rag-feature-orchestrator
description: Spring AI/RAG 기능 추가·수정·확장을 위한 5인 팀(rag-architect, spring-backend-engineer, mybatis-data-engineer, jsp-frontend-engineer, integration-qa)을 구성·조율하는 오케스트레이터. "RAG 기능 추가", "벡터 검색 구현", "문서 인덱싱", "AI 챗봇 기능", "Spring AI 통합", "RAG 기능 다시 만들어", "RAG 재실행", "RAG 업데이트", "RAG 수정", "RAG 보완", "AI 기능의 일부만 다시", "이전 RAG 결과 기반 개선", "RAG 화면만 다시" 등 RAG 관련 신규/후속 작업 요청 시 반드시 트리거. 단순 질문(설계 조언, 사용법 문의)은 직접 응답 가능.
---

## 사용 시점

이 프로젝트(guiSample)에서 Spring AI/RAG 기능을 추가·수정·확장·점진 개선할 때.
단순 질문 응답이나 1~2줄 코드 수정은 직접 처리. 다음 중 하나라도 해당하면 이 스킬로 진입:

- 새 RAG 기능 추가 (인덱싱, 검색, 챗봇 등)
- 기존 RAG 모듈 변경이 백엔드+DB+프론트 중 2개 이상 영역에 걸침
- 사용자가 "다시", "재실행", "보완", "수정" 등 후속 작업 키워드 사용

## 실행 모드

**에이전트 팀 + 점진적 QA 하이브리드**

- **Phase 1 (설계)**: rag-architect 단독 (서브 에이전트)
- **Phase 2 (구현)**: backend + data + frontend 팀 (에이전트 팀, SendMessage 자체 조율)
- **점진 QA**: 각 모듈 완료 시 integration-qa가 즉시 검증

## Phase 0: 컨텍스트 확인 — 반드시 가장 먼저 실행

`_workspace/` 디렉토리 상태를 확인하여 실행 모드 결정:

```
ls _workspace/ 2>/dev/null
```

| 상황 | 실행 모드 | 동작 |
|------|---------|------|
| `_workspace/` 미존재 | **초기 실행** | Phase 1부터 정상 진행 |
| `_workspace/` 존재 + 사용자가 "수정/보완/업데이트" 키워드 | **부분 재실행** | 영향받는 에이전트만 호출. 기존 _workspace 보존 |
| `_workspace/` 존재 + 사용자가 새 RAG 기능 요청 | **새 실행** | `mv _workspace _workspace_prev_{date}` 후 Phase 1부터 |

사용자가 모호하면 어느 모드인지 명시적으로 묻고 진행한다.

## Phase 1: 설계 (rag-architect 단독)

서브 에이전트 호출:

```
Agent(
  description: "RAG 기능 설계",
  subagent_type: "general-purpose",  # 에이전트 정의 파일은 rag-architect.md
  model: "opus",
  prompt: """
    당신은 rag-architect 에이전트입니다. .claude/agents/rag-architect.md를 먼저 읽고 역할을 인지하세요.
    스킬 spring-ai-rag-design을 사용하여 _workspace/00_architect_design.md를 작성하세요.

    사용자 요구사항: {요구사항}
    제약: build.gradle과 application.yaml의 현재 의존성을 먼저 확인하고 그 안에서 설계하세요.
  """
)
```

완료 후 사용자에게 설계 요약을 보고하고 "이대로 진행해도 되는지" 확인. **확인 전에 Phase 2로 넘어가지 않는다.**

## Phase 2: 구현 (에이전트 팀)

TeamCreate로 backend + data + frontend + qa 4인 팀 구성. TaskCreate로 작업 할당. 팀원들은 SendMessage로 자체 조율.

### 팀 구성

```
TeamCreate(
  team_name: "rag-impl",
  members: [
    {agent: "spring-backend-engineer", model: "opus"},
    {agent: "mybatis-data-engineer", model: "opus"},
    {agent: "jsp-frontend-engineer", model: "opus"},
    {agent: "integration-qa", model: "opus"}
  ]
)
```

각 팀원은 시작 시 자신의 에이전트 정의 파일과 해당 스킬을 읽는다.

### 작업 의존성

```
T1: mybatis-data-engineer — DDL + Mapper 시그니처 합의
T2: spring-backend-engineer — DTO + Service + Controller   blockedBy: [T1 시그니처]
T3: jsp-frontend-engineer — JSP + JS + CSS                  blockedBy: [T2 응답 shape 공유]
T4: integration-qa — 점진 검증 (각 모듈 완료마다)            blockedBy: none (모니터링)
```

T1과 T2는 부분 병렬: 백엔드는 DTO/Config부터 시작 가능. Mapper 주입은 T1 시그니처 확정 후.

### 데이터 전달

- **TaskCreate**: 위 T1~T4 작업 등록 + blockedBy 의존성
- **SendMessage**: 시그니처/응답 shape 공유, QA finding 전달
- **파일 기반**: `_workspace/01_backend_summary.md`, `02_data_summary.md`, `03_frontend_summary.md`, `qa_findings.md`

### QA 점진 실행

integration-qa는 backend/data/frontend 완료 SendMessage가 올 때마다 즉시 가능한 경계면을 검증. BLOCKER 발견 시 즉시 해당 팀원에 SendMessage하여 다음 작업 차단 방지.

## Phase 3: 통합 검증

모든 팀원 작업 완료 후:

1. integration-qa의 `_workspace/qa_findings.md`에 미해결 BLOCKER/MAJOR가 있으면 해당 팀원이 수정
2. `./gradlew compileJava` 실행 — 컴파일 통과 확인
3. (사용자 요청 시) `./gradlew bootRun` 또는 build로 실제 부팅 확인
4. 변경된 파일 목록과 추가된 엔드포인트/화면 요약을 사용자에게 보고

## Phase 4: 사용자 피드백 수집

작업 완료 후 반드시:

> "결과에서 개선할 부분이 있나요? 에이전트 팀 구성이나 워크플로우에 바꾸고 싶은 점이 있다면 알려주세요."

피드백이 있으면 Phase 0으로 돌아가 부분 재실행 분기.

## 에러 핸들링

| 에러 유형 | 대응 |
|---------|-----|
| 팀원 작업 실패 | 1회 재시도. 재실패 시 해당 결과 없이 진행, 보고서에 명시 |
| 컴파일 실패 (백엔드) | spring-backend-engineer가 즉시 수정. 3회 실패 시 사용자에게 보고 |
| DDL 실행 충돌 | 운영 DB에 대한 변경은 **사용자 명시적 승인** 후 실행. 승인 전에는 DDL 파일만 작성하고 멈춤 |
| 팀원 간 시그니처 협의 결렬 | 오케스트레이터가 중재. rag-architect의 설계 문서를 기준으로 결정 |
| `_workspace/` 누락된 산출물 | 해당 단계를 다시 실행. 한 사이클 동안 재실패 시 보고서에 누락 표기 |

## 팀 크기 관리

| 작업 규모 | 권장 팀 구성 |
|----------|----------|
| 소규모 (단순 검색 추가) | architect → backend + data + qa (프론트 없음) |
| 중규모 (인덱싱 + 검색 + UI) | 5명 전체 |
| 대규모 (다중 컬렉션 + 재순위 + 대시보드) | Phase별 팀 재구성 (Phase 2A: data + backend → Phase 2B: frontend + qa) |

## 테스트 시나리오

### 정상 흐름
사용자: "프로젝트 마스터 데이터를 RAG로 검색하는 기능을 추가해줘"
1. Phase 0: `_workspace/` 미존재 → 초기 실행
2. Phase 1: rag-architect가 `00_architect_design.md` 작성 → 사용자 확인
3. Phase 2: 4인 팀 구성, T1~T4 등록
   - mybatis-data-engineer: `rag_chunks` 테이블 DDL + `RagChunkMapper`
   - spring-backend-engineer: `RagQueryRestController`, `RagService`, `SpringAiConfig`
   - jsp-frontend-engineer: `rag.jsp`, `rag.js`, `rag.css`
   - integration-qa: 각 모듈 완료 즉시 5대 경계면 검증
4. Phase 3: `./gradlew compileJava` 통과, finding 정리
5. Phase 4: 사용자에게 피드백 요청

### 에러 흐름
사용자: "RAG 검색 화면만 다시 만들어줘"
1. Phase 0: `_workspace/` 존재 + "다시 만들어" → 부분 재실행
2. Phase 1 건너뜀 (설계 변경 아님)
3. Phase 2: jsp-frontend-engineer 단독 호출, 기존 `03_frontend_summary.md` 갱신
4. integration-qa는 경계면 1, 4만 재검증
5. Phase 4: 피드백 요청

## 사용 도구

- **TeamCreate, TeamDelete**: 팀 구성/해체
- **TaskCreate, TaskUpdate**: 작업 할당과 의존성
- **SendMessage**: 팀원 간 직접 통신
- **Agent**: Phase 1의 단독 호출 또는 후속 부분 재실행
- **Read, Bash**: `_workspace/` 산출물 확인

## 후속 작업 키워드 (description 트리거 보강)

description에 포함된 후속 키워드: "다시 만들어", "재실행", "업데이트", "수정", "보완", "일부만 다시", "이전 결과 기반", "화면만 다시". 이런 표현이 사용자 요청에 등장하면 반드시 Phase 0의 "부분 재실행" 분기로 진입한다.
