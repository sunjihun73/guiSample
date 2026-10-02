package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.ChatDetailDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatDetailMapper
{
    int insertChatDetail(ChatDetailDTO chatDetail);

    List<ChatDetailDTO> selectChatDetailList(@Param("sessionId") String sessionId);
}
