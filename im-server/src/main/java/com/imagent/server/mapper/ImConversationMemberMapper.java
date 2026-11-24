package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImConversationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImConversationMemberMapper extends BaseMapper<ImConversationMember> {

    /**
     * 使用 GREATEST 推进已读水位，防止弱网乱序到达的旧 ACK 将已读序号回退
     */
    @Update("UPDATE im_conversation_member " +
            "SET last_read_seq = GREATEST(last_read_seq, #{seq}) " +
            "WHERE conversation_id = #{convId} AND user_id = #{userId}")
    int advanceLastReadSeq(@Param("convId") Long convId,
                           @Param("userId") Long userId,
                           @Param("seq") Long seq);
}
