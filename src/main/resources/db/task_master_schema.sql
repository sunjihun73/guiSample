-- =====================================================================
-- task_master_schema.sql : 워크플로우 태스크의 업무 정보(이름) 테이블
-- ---------------------------------------------------------------------
-- ⚠ 이 파일은 "사용자 승인 후 수동 적용" 대상이다.
--    애플리케이션 부팅/마이그레이션으로 자동 실행하지 않는다.
--
-- ⚠ 적용 대상 DB 를 주의할 것 — 여기는 **앱 DB(pgvector, 5432)** 다.
--    워크플로우 엔진 테이블(ACT_*)이 있는 flowable-db(5433)가 아니다.
--    엔진은 진행 상태만, 이 테이블은 업무 정보만 갖는다(ADR 0006).
-- =====================================================================

-- ---------------------------------------------------------------------
-- public.task_master
--   task_id 는 Flowable 프로세스 인스턴스의 business key 및 프로세스 변수
--   taskNo 와 "같은 값"이다. 두 DB 를 잇는 유일한 키이며 물리 FK 는 없다
--   (DB 가 서로 다르므로 걸 수 없다).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.task_master (
    task_id             varchar(100)  NOT NULL,                 -- 태스크 ID (예: WF-20260922-231632-31C6) = 엔진 business key
    task_name           varchar(200)  NOT NULL,                 -- 태스크 이름 (스텝1 에서 사용자가 입력)
    task_owner_user_id  varchar(50)   NOT NULL,                 -- 소유자 = user_master.user_name (조회 필터 기준)
    update_user_id      varchar(100),                           -- 수정자 = user_master.user_name
    update_date         timestamp     DEFAULT NOW(),            -- 수정 시각
    create_user_id      varchar(100),                           -- 생성자 = user_master.user_name
    create_date         timestamp     DEFAULT NOW(),            -- 생성 시각
    CONSTRAINT pk_task_master PRIMARY KEY (task_id)
);

-- 소유자별 조회 + 최신순 정렬용
CREATE INDEX IF NOT EXISTS idx_task_master_owner
    ON public.task_master (task_owner_user_id, create_date DESC);
