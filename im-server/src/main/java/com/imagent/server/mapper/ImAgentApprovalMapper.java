package com.imagent.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imagent.server.entity.ImAgentApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImAgentApprovalMapper extends BaseMapper<ImAgentApproval> {

    /**
     * 乐观 CAS 更新：仅当审批单仍处于 PENDING 且未过期时才允许流转，防止重复点击导致工具重复执行
     */
    @Update("UPDATE im_agent_approval " +
            "SET decision = #{decision}, approver_id = #{approverId}, comment = #{comment}, decided_at = NOW(3) " +
            "WHERE id = #{approvalId} AND task_id = #{taskId} AND decision = 'PENDING' AND expire_at > NOW(3)")
    int decideIfPending(@Param("approvalId") Long approvalId,
                        @Param("taskId") Long taskId,
                        @Param("approverId") Long approverId,
                        @Param("decision") String decision,
                        @Param("comment") String comment);
}
