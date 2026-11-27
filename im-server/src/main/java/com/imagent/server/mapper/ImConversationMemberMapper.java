package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImConversationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImConversationMemberMapper extends BaseMapper<ImConversationMember> {

    /**
     * 使用 GREATEST 推进已读水位并清除手动未读标记，防止弱网乱序到达的旧 ACK 将已读序号回退
     */
    @Update("UPDATE im_conversation_member " +
            "SET last_read_seq = GREATEST(last_read_seq, #{seq}), manual_unread = 0 " +
            "WHERE conversation_id = #{convId} AND user_id = #{userId}")
    int advanceLastReadSeq(@Param("convId") Long convId,
                           @Param("userId") Long userId,
                           @Param("seq") Long seq);

    /**
     * 当会话收到新消息时，自动取消该会话内所有成员的隐藏状态
     */
    @Update("UPDATE im_conversation_member " +
            "SET hidden = 0 " +
            "WHERE conversation_id = #{convId} AND hidden = 1")
    int unhideAllMembers(@Param("convId") Long convId);

    /**
     * 手动标为未读并取消隐藏（保留真实已读水位）
     */
    @Update("UPDATE im_conversation_member " +
            "SET manual_unread = 1, hidden = 0 " +
            "WHERE conversation_id = #{convId} AND user_id = #{userId}")
    int markManualUnread(@Param("convId") Long convId,
                         @Param("userId") Long userId);

    /**
     * 个人删除该聊天：隐藏会话、记录清空历史水位并归零未读与置顶
     */
    @Update("UPDATE im_conversation_member " +
            "SET hidden = 1, " +
            "    cleared_before_seq = GREATEST(cleared_before_seq, #{seq}), " +
            "    last_read_seq = GREATEST(last_read_seq, #{seq}), " +
            "    manual_unread = 0, " +
            "    pinned = 0 " +
            "WHERE conversation_id = #{convId} AND user_id = #{userId}")
    int hideAndClearForMember(@Param("convId") Long convId,
                              @Param("userId") Long userId,
                              @Param("seq") Long seq);
}
