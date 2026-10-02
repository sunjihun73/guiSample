# 02. 데이터 계층 요약 (T1 — mybatis-data-engineer)

> 작성: mybatis-data-engineer | 날짜: 2026-07-08
> 근거: `_workspace/00_architect_design.md` (2. 데이터 모델 / 11. T1)
> 상태: `./gradlew compileJava` BUILD SUCCESSFUL

## 1. 생성 파일 목록

| 유형 | 경로 |
|------|------|
| DDL | `src/main/resources/db/chat_schema.sql` (chat_master + chat_detail + idx_chat_detail_session) |
| DTO | `src/main/java/kr/co/jihun/guisample/dto/ChatMasterDTO.java` |
| DTO | `src/main/java/kr/co/jihun/guisample/dto/ChatDetailDTO.java` |
| Mapper IF | `src/main/java/kr/co/jihun/guisample/mapper/ChatMasterMapper.java` |
| Mapper IF | `src/main/java/kr/co/jihun/guisample/mapper/ChatDetailMapper.java` |
| Mapper XML | `src/main/resources/mapper/ChatMasterMapper.xml` |
| Mapper XML | `src/main/resources/mapper/ChatDetailMapper.xml` |

> ⚠ `chat_schema.sql` 은 **파일만 작성**. DB 미실행. 사용자 승인 후 수동 적용.

## 2. Mapper 메서드 시그니처 (backend 주입용)

### ChatMasterMapper (`kr.co.jihun.guisample.mapper.ChatMasterMapper`)
```java
List<ChatMasterDTO> selectChatMasterList(HashMap<String, Object> param);  // create_date DESC, 선택적 페이징
int                 countChatMaster(HashMap<String, Object> param);
int                 insertChatMaster(ChatMasterDTO chatMaster);           // create/update_date = NOW()
```
- 페이징 param 키: `pageSize`, `startRow` (둘 다 있을 때만 LIMIT/OFFSET 적용).
- 필터 param 키(선택): `chatSessionId`, `chatOwnerUserId`, `chatTitleName`(LIKE).
- insert 시 DTO에 채울 값: `chatSessionId`(UUID), `chatTitleName`("새로운채팅"), `chatOwnerUserId`/`createUserId`/`updateUserId`("sunjeehun"). 날짜는 XML `NOW()`가 처리.

호출 예시:
```java
ChatMasterDTO dto = new ChatMasterDTO();
dto.setChatSessionId(UUID.randomUUID().toString());
dto.setChatTitleName("새로운채팅");
dto.setChatOwnerUserId("sunjeehun");
dto.setCreateUserId("sunjeehun");
dto.setUpdateUserId("sunjeehun");
chatMasterMapper.insertChatMaster(dto);

HashMap<String,Object> p = new HashMap<>();
p.put("startRow", 0); p.put("pageSize", 100);
List<ChatMasterDTO> rows = chatMasterMapper.selectChatMasterList(p);  // 최신순
int total = chatMasterMapper.countChatMaster(p);
```

### ChatDetailMapper (`kr.co.jihun.guisample.mapper.ChatDetailMapper`)
```java
int                 insertChatDetail(ChatDetailDTO chatDetail);            // create/update_date = NOW()
List<ChatDetailDTO> selectChatDetailList(@Param("sessionId") String sessionId);  // chat_content_id ASC
```
- `chat_content_id` 는 BIGSERIAL 자동 증가 → insert 시 미설정.
- user 저장: `role="user"`, model/토큰 null. assistant 저장: `role="assistant"`, `model="gpt-4o-mini"`, `promptTokens`/`completionTokens`(usage null이면 null 저장 가능).
- insert 시 DTO에 채울 값: `chatSessionId`, `chatContent`, `role`, (assistant면 `model`,`promptTokens`,`completionTokens`), `createUserId`/`updateUserId`("sunjeehun").

호출 예시:
```java
ChatDetailDTO u = new ChatDetailDTO();
u.setChatSessionId(sessionId); u.setChatContent(query); u.setRole("user");
u.setCreateUserId("sunjeehun"); u.setUpdateUserId("sunjeehun");
chatDetailMapper.insertChatDetail(u);

List<ChatDetailDTO> msgs = chatDetailMapper.selectChatDetailList(sessionId);  // 시간순
```

## 3. ResultMap ↔ DTO 매핑

### chat_master → ChatMasterDTO (`chatMasterResultMap`)
| DB 컬럼 | DTO 필드 | 타입 |
|---------|----------|------|
| chat_session_id (id) | chatSessionId | String |
| chat_title_name | chatTitleName | String |
| chat_owner_user_id | chatOwnerUserId | String |
| update_user_id | updateUserId | String |
| update_date | updateDate | String |
| create_user_id | createUserId | String |
| create_date | createDate | String |

### chat_detail → ChatDetailDTO (`chatDetailResultMap`)
| DB 컬럼 | DTO 필드 | 타입 |
|---------|----------|------|
| chat_content_id (id) | chatContentId | Long |
| chat_session_id | chatSessionId | String |
| chat_content | chatContent | String |
| role | role | String |
| model | model | String |
| prompt_tokens | promptTokens | Integer |
| completion_tokens | completionTokens | Integer |
| update_user_id | updateUserId | String |
| update_date | updateDate | String |
| create_user_id | createUserId | String |
| create_date | createDate | String |

## 4. 테이블 DDL 요약

- **chat_master** (신규): PK `chat_session_id varchar(100)`. `chat_title_name`/`chat_owner_user_id` NOT NULL. 감사 컬럼 4개, 날짜 DEFAULT NOW(). 정렬키 `create_date`.
- **chat_detail** (신규): PK `chat_content_id BIGSERIAL`. `chat_session_id`(논리 FK, 물리 FK 없음)/`role` NOT NULL. `model`/`prompt_tokens`/`completion_tokens` nullable(usage 미확보 fallback 대응).
- **인덱스**: `idx_chat_detail_session (chat_session_id, chat_content_id)` — 세션별 시간순 조회 커버링.
- 모든 CREATE 는 `IF NOT EXISTS` 멱등. **미실행 상태 — 사용자 승인 후 적용.**

## 5. 검증

- Mapper XML namespace = interface FQN 일치 (ChatMasterMapper / ChatDetailMapper) ✔
- resultMap 모든 property 가 DTO 필드에 존재 ✔
- `#{}` 바인딩만 사용, `${}` 없음 ✔
- `./gradlew compileJava` BUILD SUCCESSFUL ✔

## 6. 백엔드(T2)에 전달할 주의점

- `selectChatDetailList` 는 String 파라미터에 `@Param("sessionId")` 적용 → XML `#{sessionId}` 바인딩.
- 세션 소유자/감사자 고정값 `"sunjeehun"` 은 서비스에서 세팅(DTO), 날짜는 mapper `NOW()`.
- project_master 계층은 미변경 (채팅과 무관).

## 7. T5 추가 — 세션 제목 자동 갱신 (첫 질문 요약)

> 근거: 후속 요구(코디네이터 T5). 기존 statement 미변경, 신규만 추가. `./gradlew compileJava` BUILD SUCCESSFUL.

ChatMasterMapper 에 메서드 2개 추가:
```java
ChatMasterDTO selectChatMaster(@Param("sessionId") String sessionId);   // 단건 조회, chatMasterResultMap 재사용
int updateChatMasterTitle(@Param("sessionId") String sessionId,
                          @Param("title") String title,
                          @Param("updateUserId") String updateUserId);   // 제목 갱신 + update_date=NOW()
```
- 다중 파라미터이므로 `@Param` 사용(라운드1 `selectChatDetailList` 패턴과 동일 접근).
- XML `updateChatMasterTitle`: `UPDATE chat_master SET chat_title_name=#{title}, update_user_id=#{updateUserId}, update_date=NOW() WHERE chat_session_id=#{sessionId}`.
- `selectChatMaster`: `chatMasterResultMap` 재사용, `WHERE chat_session_id=#{sessionId}`.
- 사용 지침: 백엔드가 첫 질문 저장 후 `selectChatMaster(sessionId)` 로 현재 제목이 기본값(`"새로운채팅"`)인지 확인 → 기본값이면 `updateChatMasterTitle(sessionId, 요약제목, "sunjeehun")` 호출.
- 반환: `updateChatMasterTitle` 는 영향 행 수(int). 세션 없음/미갱신 시 0.
