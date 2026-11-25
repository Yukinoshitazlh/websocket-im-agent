package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImGroupReminder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImGroupReminderMapper extends BaseMapper<ImGroupReminder> {

    /**
     * 乐观 CAS 抢占触发权：防止多节点定时任务重复推送同一条提醒
     */
    @Update("UPDATE im_group_reminder SET status = 'SENT' WHERE id = #{id} AND status = 'PENDING'")
    int markSentIfPending(@Param("id") Long id);

    /**
     * 数据库兜底补偿查询（防止 Redis 重启丢失 ZSet 延迟项）
     */
    @Select("SELECT * FROM im_group_reminder WHERE status = 'PENDING' AND remind_at <= NOW(3) ORDER BY remind_at ASC LIMIT #{limit}")
    List<ImGroupReminder> selectDuePending(@Param("limit") int limit);
}
