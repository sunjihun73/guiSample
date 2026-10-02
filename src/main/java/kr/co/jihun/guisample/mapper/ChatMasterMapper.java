package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.ChatMasterDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.HashMap;
import java.util.List;

@Mapper
public interface ChatMasterMapper
{
    List<ChatMasterDTO> selectChatMasterList(HashMap<String, Object> param);

    int countChatMaster(HashMap<String, Object> param);

    int insertChatMaster(ChatMasterDTO chatMaster);

    /**
     * 세션 단건 조회.
     *
     * @param sessionId    조회할 세션 ID
     * @param ownerUserId  로그인 사용자명(user_master.user_name). 소유자가 아니면 null 이 반환된다.
     */
    ChatMasterDTO selectChatMaster(@Param("sessionId") String sessionId,
                                   @Param("ownerUserId") String ownerUserId);

    /**
     * 세션 제목 변경. 소유자(chat_owner_user_id)가 일치할 때만 반영된다.
     *
     * <p>{@code updateUserId}(감사 컬럼에 찍을 값)와 {@code ownerUserId}(소유자 조건)는 현재
     * 같은 값이지만 의미가 다르므로 분리한다 — 나중에 관리자가 남의 세션을 손보는 경로가 생기면
     * 둘이 달라진다.
     *
     * @return 반영된 행 수 (0 = 대상 없음 또는 소유자 불일치)
     */
    int updateChatMasterTitle(@Param("sessionId") String sessionId,
                              @Param("title") String title,
                              @Param("updateUserId") String updateUserId,
                              @Param("ownerUserId") String ownerUserId);
}
