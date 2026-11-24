package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImConversationMapper extends BaseMapper<ImConversation> {

    @Update("UPDATE im_conversation SET current_seq = #{seq}, last_msg_id = #{msgId}, " +
            "last_msg_preview = #{preview}, last_msg_time = NOW(3) WHERE id = #{convId}")
    int updateLastMessage(@Param("convId") Long convId,
                          @Param("seq") Long seq,
                          @Param("msgId") Long msgId,
                          @Param("preview") String preview);
}
