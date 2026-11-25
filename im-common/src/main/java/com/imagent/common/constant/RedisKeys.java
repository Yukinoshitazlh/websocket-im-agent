package com.imagent.common.constant;

/**
 * Redis Key 集中管理，杜绝魔法字符串散落各模块
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 在线用户路由：im:route:user:{userId} -> gatewayNodeId */
    public static String userRoute(Long userId) {
        return "im:route:user:" + userId;
    }

    /** 会话级严格递增序列号：im:seq:conv:{conversationId} */
    public static String conversationSeq(Long conversationId) {
        return "im:seq:conv:" + conversationId;
    }

    /** 会话 seq 冷启动初始化分布式锁：im:lock:seq:init:{conversationId} */
    public static String seqInitLock(Long conversationId) {
        return "im:lock:seq:init:" + conversationId;
    }

    /** 客户端消息发送幂等键：im:idemp:{senderId}:{clientMsgId} */
    public static String msgIdempotency(Long senderId, String clientMsgId) {
        return "im:idemp:" + senderId + ":" + clientMsgId;
    }

    /** 用户会话未读数 Hash：im:unread:{userId} (field=convId, value=count) */
    public static String userUnreadHash(Long userId) {
        return "im:unread:" + userId;
    }

    /** Agent 短期滑动窗口记忆：im:agent:ctx:{conversationId} */
    public static String agentContext(Long conversationId) {
        return "im:agent:ctx:" + conversationId;
    }

    /** Agent 流式增量内容缓存（用于中途刷新/重连续传）：im:agent:stream:{taskId} */
    public static String agentStreamBuffer(Long taskId) {
        return "im:agent:stream:" + taskId;
    }

    /** Agent 任务取消标记：im:agent:cancel:{taskId} */
    public static String agentCancelFlag(Long taskId) {
        return "im:agent:cancel:" + taskId;
    }

    /** 会话成员缓存 Set：im:conv:members:{conversationId} */
    public static String conversationMembers(Long conversationId) {
        return "im:conv:members:" + conversationId;
    }

    /** 用户昵称缓存：im:user:nick:{userId} */
    public static String userNickname(Long userId) {
        return "im:user:nick:" + userId;
    }

    /** 用户头像缓存：im:user:avatar:{userId} */
    public static String userAvatar(Long userId) {
        return "im:user:avatar:" + userId;
    }

    /** 普通消息发送限流键 */
    public static String chatRateLimit(Long userId) {
        return "im:rl:chat:" + userId;
    }

    /** Agent 短周期令牌桶限流键 */
    public static String agentRateLimit(Long userId) {
        return "im:rl:agent:" + userId;
    }

    /** Agent 每日调用配额键 */
    public static String agentDailyQuota(Long userId, String yyyyMMdd) {
        return "im:rl:agent:daily:" + userId + ":" + yyyyMMdd;
    }

    /** 群日程提醒延迟队列 ZSet（score = 触发时间戳 ms，member = reminderId） */
    public static String reminderDelayZSet() {
        return "im:delay:reminders";
    }

    /** 会话成员已读水位 Hash：im:conv:readseq:{convId} (field=userId, value=lastReadSeq) */
    public static String conversationReadSeqHash(Long conversationId) {
        return "im:conv:readseq:" + conversationId;
    }

    /** 用户会话被 @ 提醒水位 Hash：im:unread:at:{userId} (field=convId, value=firstAtSeq) */
    public static String userMentionHash(Long userId) {
        return "im:unread:at:" + userId;
    }
}
