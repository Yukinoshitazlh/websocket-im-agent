package com.imagent.agent.mapper;

import com.imagent.agent.entity.ChatHistorySnippet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 严格限定 conversation_id 的只读会话查询（杜绝跨群越权检索）
 */
@Mapper
public interface ConversationReadMapper {

    @Select("SELECT user_id FROM im_conversation_member WHERE conversation_id = #{conversationId}")
    List<Long> selectMemberIds(@Param("conversationId") Long conversationId);

    @Select("SELECT m.seq, COALESCE(u.nickname, CONCAT('用户', m.sender_id)) AS senderNickname, " +
            "m.content, m.msg_type AS msgType " +
            "FROM im_message m LEFT JOIN im_user u ON m.sender_id = u.id " +
            "WHERE m.conversation_id = #{conversationId} AND m.status = 1 " +
            "ORDER BY m.seq DESC LIMIT #{limit}")
    List<ChatHistorySnippet> selectRecentMessages(@Param("conversationId") Long conversationId,
                                                  @Param("limit") int limit);

    @Select("SELECT m.seq, COALESCE(u.nickname, CONCAT('用户', m.sender_id)) AS senderNickname, " +
            "m.content, m.msg_type AS msgType " +
            "FROM im_message m LEFT JOIN im_user u ON m.sender_id = u.id " +
            "WHERE m.conversation_id = #{conversationId} AND m.status = 1 " +
            "AND m.content LIKE CONCAT('%', #{keyword}, '%') " +
            "ORDER BY m.seq DESC LIMIT #{limit}")
    List<ChatHistorySnippet> searchMessagesByKeyword(@Param("conversationId") Long conversationId,
                                                     @Param("keyword") String keyword,
                                                     @Param("limit") int limit);
}
