package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.dto.ChatDetailDTO;
import kr.co.jihun.guisample.dto.ChatMasterDTO;
import kr.co.jihun.guisample.mapper.ChatDetailMapper;
import kr.co.jihun.guisample.mapper.ChatMasterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 채팅 세션(chat_master) + 대화 메시지(chat_detail) 비즈니스 계층.
 *
 * <p>설계(00_architect_design.md §8) 원칙:
 * <ul>
 *   <li>세션 소유자/감사 컬럼은 <b>로그인 사용자명</b>(user_master.user_name). 컨트롤러가 세션에서
 *       꺼내 인자로 넘겨 준다 — 이 계층은 HTTP 세션을 알지 못한다.</li>
 *   <li>저장 메서드는 각각 독립된 짧은 트랜잭션 — 스트림 전체를 트랜잭션으로 감싸지 않는다.</li>
 *   <li>날짜(create/update_date)는 mapper XML 의 NOW() 가 처리.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService
{
    /** 신규 세션 기본 제목. */
    public static final String DEFAULT_TITLE = "새로운채팅";

    private final ChatMasterMapper chatMasterMapper;
    private final ChatDetailMapper chatDetailMapper;

    /**
     * 새 채팅 세션을 생성한다.
     *
     * @param title    세션 제목. null/공백이면 "새로운채팅".
     * @param userName 로그인 사용자명 — chat_owner_user_id 로 저장되며 이후 목록 조회 조건의 기준이 된다
     * @return 생성된 세션 DTO(생성 직후 상태 — createDate 는 미조회 상태로 null 일 수 있음)
     */
    @Transactional
    public ChatMasterDTO createSession(String title, String userName)
    {
        ChatMasterDTO dto = new ChatMasterDTO();
        dto.setChatSessionId(UUID.randomUUID().toString());
        dto.setChatTitleName((title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim());
        dto.setChatOwnerUserId(userName);
        dto.setCreateUserId(userName);
        dto.setUpdateUserId(userName);

        chatMasterMapper.insertChatMaster(dto);
        return dto;
    }

    /** 세션 목록 조회 (create_date DESC). 페이징 param(startRow/pageSize)은 호출측에서 세팅. */
    public List<ChatMasterDTO> selectSessions(HashMap<String, Object> param)
    {
        return chatMasterMapper.selectChatMasterList(param);
    }

    /** 세션 전체 건수. */
    public int countSessions(HashMap<String, Object> param)
    {
        return chatMasterMapper.countChatMaster(param);
    }

    /**
     * 세션별 대화 조회 (chat_content_id ASC).
     *
     * <p>chat_detail 에는 소유자 컬럼이 없으므로, 상위 세션(chat_master)의 소유권을 먼저 확인해
     * 남의 대화가 넘어가지 않게 한다. 소유자가 아니면 빈 목록.
     *
     * @param sessionId 조회할 세션 ID
     * @param userName  로그인 사용자명
     */
    public List<ChatDetailDTO> selectMessages(String sessionId, String userName)
    {
        if (selectSession(sessionId, userName) == null)
        {
            return List.of();
        }
        return chatDetailMapper.selectChatDetailList(sessionId);
    }

    /**
     * 세션 단건 조회. 없거나 소유자가 아니면 null.
     *
     * @param sessionId 조회할 세션 ID
     * @param userName  로그인 사용자명 — chat_owner_user_id 와 대조된다
     */
    public ChatMasterDTO selectSession(String sessionId, String userName)
    {
        return chatMasterMapper.selectChatMaster(sessionId, userName);
    }

    /**
     * 세션 제목이 아직 기본값({@link #DEFAULT_TITLE})일 때만 새 제목으로 1회 자동 변경한다.
     * <p>사용자가 이미 수동으로 바꾼 제목은 건드리지 않는 안전장치. 독립 트랜잭션.
     *
     * @param sessionId 대상 세션
     * @param newTitle  적용할 제목(공백이면 무시)
     * @param userName  로그인 사용자명 — 소유자가 아니면 변경하지 않는다
     * @return 실제로 변경했으면 true
     */
    @Transactional
    public boolean autoRenameTitleIfDefault(String sessionId, String newTitle, String userName)
    {
        if (sessionId == null || sessionId.isBlank() || newTitle == null || newTitle.isBlank())
        {
            return false;
        }

        ChatMasterDTO session = chatMasterMapper.selectChatMaster(sessionId, userName);
        if (session == null || !DEFAULT_TITLE.equals(session.getChatTitleName()))
        {
            // 세션 없음/소유자 아님 or 이미 수동 변경됨 → 건드리지 않음
            return false;
        }

        /* 마지막 인자가 소유자 조건 — 위 조회와 이 UPDATE 사이의 변경까지 막는다. */
        int updated = chatMasterMapper.updateChatMasterTitle(
                sessionId, newTitle.trim(), userName, userName);
        return updated > 0;
    }

    /**
     * user 발화 저장 (role=user, model/토큰 null). 스트림 시작 전 동기 커밋.
     * 독립 트랜잭션.
     *
     * @param userName 로그인 사용자명 — 감사 컬럼(create/update_user_id)에 기록된다
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content, String userName)
    {
        ChatDetailDTO dto = new ChatDetailDTO();
        dto.setChatSessionId(sessionId);
        dto.setChatContent(content);
        dto.setRole("user");
        dto.setCreateUserId(userName);
        dto.setUpdateUserId(userName);

        chatDetailMapper.insertChatDetail(dto);
    }

    /**
     * assistant 발화 저장 (role=assistant, model/토큰 포함). 스트림 완료 후 커밋.
     * 독립 트랜잭션. usage 미확보 시 promptTokens/completionTokens 는 null 그대로 저장.
     *
     * @param userName 로그인 사용자명 — 감사 컬럼(create/update_user_id)에 기록된다
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String content, String model,
                                     Integer promptTokens, Integer completionTokens,
                                     String userName)
    {
        ChatDetailDTO dto = new ChatDetailDTO();
        dto.setChatSessionId(sessionId);
        dto.setChatContent(content);
        dto.setRole("assistant");
        dto.setModel(model);
        dto.setPromptTokens(promptTokens);
        dto.setCompletionTokens(completionTokens);
        dto.setCreateUserId(userName);
        dto.setUpdateUserId(userName);

        chatDetailMapper.insertChatDetail(dto);
    }
}
