-- =====================================================================
-- chat_schema.sql : RAG 채팅 세션 기능용 스키마 (chat_master + chat_detail)
-- 설계: _workspace/00_architect_design.md (2. 데이터 모델)
-- ---------------------------------------------------------------------
-- ⚠ 이 파일은 "사용자 승인 후 수동 적용" 대상이다.
--    애플리케이션 부팅/마이그레이션으로 자동 실행하지 않는다.
--    DBA/사용자가 검토 후 psql 등으로 직접 실행할 것.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 2.1 채팅 세션 테이블 : public.chat_master
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.chat_master (
    chat_session_id     varchar(100)  NOT NULL,                 -- 세션 ID (UUID)
    chat_title_name     varchar(200)  NOT NULL,                 -- 세션 제목 (신규 = '새로운채팅')
    chat_owner_user_id  varchar(50)   NOT NULL,                 -- 소유자 = user_master.user_name (조회 필터 기준)
    update_user_id      varchar(100),                           -- 수정자 = user_master.user_name
    update_date         timestamp     DEFAULT NOW(),            -- 수정 시각
    create_user_id      varchar(100),                           -- 생성자 = user_master.user_name
    create_date         timestamp     DEFAULT NOW(),            -- 생성 시각 (목록 정렬 키)
    CONSTRAINT pk_chat_master PRIMARY KEY (chat_session_id)
);

-- ---------------------------------------------------------------------
-- 2.2 채팅 메시지 테이블 : public.chat_detail
--   (chat_session_id 는 chat_master 와 논리 FK, 물리 FK 는 두지 않음)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.chat_detail (
    chat_content_id     BIGSERIAL     NOT NULL,                 -- 메시지 고유 ID (자동 증가)
    chat_session_id     varchar(100)  NOT NULL,                 -- = chat_master.chat_session_id
    chat_content        text,                                   -- 발화 본문
    role                varchar(20)   NOT NULL,                 -- 'user' | 'assistant'
    model               varchar(100),                           -- assistant 행: 예) gpt-4o-mini
    prompt_tokens       int,                                    -- assistant 행: 스트리밍 usage
    completion_tokens   int,                                    -- assistant 행: 스트리밍 usage
    update_user_id      varchar(100),                           -- 수정자 = user_master.user_name
    update_date         timestamp     DEFAULT NOW(),            -- 수정 시각
    create_user_id      varchar(100),                           -- 생성자 = user_master.user_name
    create_date         timestamp     DEFAULT NOW(),            -- 생성 시각
    CONSTRAINT pk_chat_detail PRIMARY KEY (chat_content_id)
);

-- 세션별 대화 조회(ORDER BY chat_content_id ASC) 커버링 인덱스
CREATE INDEX IF NOT EXISTS idx_chat_detail_session
    ON public.chat_detail (chat_session_id, chat_content_id);
