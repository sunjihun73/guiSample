# 0004. SGuard 가드레일 모델 적용 보류

- 상태(Status): 보류(Deferred)
- 날짜(Date): 2026-09-14

## 배경(Context)

[0002](0002-rag-guardrail-litellm.md) 결정에 따라 입력 가드레일은 애플리케이션이 아니라 **LiteLLM 프록시**가 담당한다. 애플리케이션에는 가드레일 판정 코드가 없고, LiteLLM이 차단 시 반환하는 HTTP 400을 사용자 노출용 거부 메시지로 바꾸는 책임만 진다(`AIRestController.toUserMessage()`).

LiteLLM 뒤에 붙는 가드레일 분류 모델은 **LM Studio**가 OpenAI 호환 API로 서빙한다(`e2e/specs/guardrail.spec.ts` 주석, `e2e/README.md` — `npm run test:all` 이 LM Studio를 요구하는 이유가 이것이다).

이 구성에서 기존 분류 모델을 **SGuard** 계열 가드레일 모델로 교체하는 것을 검토했다. 교체 대상은 애플리케이션 코드가 아니라 LiteLLM이 호출하는 모델 하나뿐이므로, 성공했다면 앱 재배포 없이 설정만으로 끝나는 변경이었다.

## 결정(Decision)

**SGuard 모델 적용을 보류한다.** 가드레일 분류 모델은 현행 구성을 유지한다.

애플리케이션·LiteLLM·E2E 어느 쪽에도 SGuard 관련 설정을 넣지 않는다. 현재 이 저장소에 SGuard를 가리키는 코드나 설정은 존재하지 않으며, 그 상태가 의도된 것임을 이 문서로 남긴다.

## 근거(Rationale)

### LM Studio 서빙 경로에서 커스텀 스키마가 통과되지 않는다

SGuard 같은 안전성 분류(safety classification) 모델은 일반 대화형 모델과 달리 **모델 고유의 입력 포맷과 출력 스키마**를 전제로 동작한다. 정해진 형태로 입력을 구성하고 정해진 라벨 형태로 출력을 받아야 판정이 성립한다.

LM Studio의 OpenAI 호환 엔드포인트는 요청을 일반적인 chat completions 형태로 정규화해 넘기므로, 이 경로에서는 모델이 요구하는 커스텀 스키마를 그대로 통과시킬 수 없었다. 스키마가 깨진 채 호출되면 분류 결과를 신뢰할 수 없고, 가드레일로서는 **틀린 판정이 판정 없음보다 나쁘다** — 통과시켜야 할 질문을 막거나, 막아야 할 질문을 통과시킨다.

### 우회하려면 별도 서빙 계층이 필요하다

해결책은 LM Studio를 거치지 않고 **transformers 래퍼로 모델을 직접 분리 서빙**하는 것이다. 모델 고유의 입력 구성과 출력 파싱을 래퍼가 책임지고, LiteLLM에는 OpenAI 호환 형태로 노출하는 구조다.

그러나 이는 다음을 새로 떠안는 일이다.

- 파이썬 서빙 프로세스 하나가 운영 대상에 추가된다(현재 구성: 앱 · pgvector · Keycloak · LiteLLM · nginx · LM Studio).
- 그 프로세스의 기동·헬스체크·수명 관리가 E2E 프리플라이트(`e2e/setup/preflight.ts`)에도 반영돼야 한다.
- 모델 로딩에 필요한 GPU/메모리를 LM Studio와 나눠 쓰게 된다.

본 프로젝트는 학습·검증 목적의 토이 프로젝트이고([0001](0001-use-pgvector.md)), 현행 가드레일도 동작한다. 교체로 얻는 이득이 서빙 계층을 하나 더 운영하는 비용을 지금 시점에 정당화하지 못한다고 판단했다.

## 결과(Consequences)

### 유지되는 것

- 가드레일 파이프라인은 [0002](0002-rag-guardrail-litellm.md)에 기술된 그대로다. 애플리케이션 코드 변경 없음.
- 차단 판정은 여전히 LiteLLM 안에서 일어나고, 앱은 400 응답 본문의 표식(`guardrail`, `violated`, `unsafe`, `content safety`, `blocked`, `moderation`)으로 가드레일 차단과 설정 오류를 구분한다.
- `@guardrail` 태그 E2E는 계속 LM Studio 기동을 전제로 한다(`npm test` 는 제외, `npm run test:all` 은 포함).

### 남는 위험

- 가드레일 분류 모델이 죽어 있어도 **fail-open** 이라 차단이 조용히 사라진다. `guardrail.spec.ts` 의 `beforeAll` 이 LiteLLM 생존을 먼저 확인하는 이유이며, 이 위험은 모델 교체 여부와 무관하게 그대로 남는다.
- 모델 교체 검토가 보류됐을 뿐 분류 품질 평가가 끝난 것은 아니다. 현행 모델의 오탐/미탐 수준은 별도로 측정된 바 없다.

### 재검토 조건

다음 중 하나가 성립하면 다시 검토한다.

1. transformers 래퍼를 별도 프로세스로 서빙·운영할 여력이 생겼을 때.
2. LM Studio(또는 대체 서버)가 모델별 커스텀 스키마를 손실 없이 전달하는 경로를 지원할 때.
3. 현행 가드레일의 오탐·미탐이 실제로 문제가 될 때 — 이 경우엔 모델 교체가 비용을 정당화한다.

재개할 경우 변경 범위는 **LiteLLM 설정 + 서빙 프로세스**에 국한되며, 애플리케이션 코드는 건드리지 않는다(그것이 [0002](0002-rag-guardrail-litellm.md)에서 가드레일을 앱 밖으로 뺀 목적이다).

## 관련 문서

- [0002. RAG 채팅 파이프라인과 가드레일 위치(LiteLLM 위임)](0002-rag-guardrail-litellm.md)
- [0001. pgvector를 벡터 저장소로 사용](0001-use-pgvector.md)
