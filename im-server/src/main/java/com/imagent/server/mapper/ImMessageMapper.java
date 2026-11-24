package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    @Select("SELECT COALESCE(MAX(seq), 0) FROM im_message WHERE conversation_id = #{conversationId}")
    Long selectMaxSeq(@Param("conversationId") Long conversationId);
}
