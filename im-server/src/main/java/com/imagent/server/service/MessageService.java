package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.mq.GatewayPushEvent;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.ChatPushPayload;
import com.imagent.common.protocol.payload.ChatSendPayload;
import com.imagent.common.protocol.payload.ServerAckPayload;
import com.imagent.server.dto.MessageVO;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);

    private final ImMessageMapper messageMapper;
    private final ImConversationMapper conversationMapper;
    private final ImUserMapper userMapper;
    private final ConversationService conversationService;
    private final SeqGeneratorService seqGeneratorService;
    private final UnreadService unreadService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public List<MessageVO> listHistory(Long userId, Long conversationId, int limit) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<ImMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ImMessage>()
                        .eq(ImMessage::getConversationId, conversationId)
                        .orderByDesc(ImMessage::getSeq)
                        .last("LIMIT " + safeLimit));
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(messages);
        return toMessageVOs(messages);
    }

    /**
     * 推拉结合离线增量同步：基于 uk_conv_seq (conversation_id, seq) 联合索引范围扫描
     */
    public List<MessageVO> syncAfterSeq(Long userId, Long conversationId, Long afterSeq, int limit) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        long safeAfterSeq = (afterSeq == null || afterSeq < 0) ? 0L : afterSeq;
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<ImMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ImMessage>()
                        .eq(ImMessage::getConversationId, conversationId)
                        .gt(ImMessage::getSeq, safeAfterSeq)
                        .orderByAsc(ImMessage::getSeq)
                        .last("LIMIT " + safeLimit));
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        return toMessageVOs(messages);
    }

    /**
     * 处理客户端已读回执（MSG_ACK 或 HTTP 已读上报）
     */
    public void ackRead(Long userId, Long conversationId, Long seq) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该会话已读状态");
        }
        unreadService.markAsRead(userId, conversationId, seq);
    }

    /**
     * 可靠消息受理：
     * 1. Redis 幂等缓存拦截弱网重传；
     * 2. TransactionTemplate 事务内分配并发安全 seq 并落库（MySQL 唯一索引 uk_sender_client_msg 兜底并发重传）；
     * 3. 事务提交后再递增未读数与投递 MQ，避免消费端/客户端拉取到未提交事务数据。
     */
    public ServerAckPayload processIncomingMessage(Long senderId, ChatSendPayload payload) {
        Long convId = payload.getConversationId();
        if (convId == null || payload.getContent() == null || payload.getContent().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话ID和消息内容不能为空");
        }
        if (!conversationService.isMember(convId, senderId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权向该会话发送消息");
        }

        String clientMsgId = (payload.getClientMsgId() == null || payload.getClientMsgId().isBlank())
                ? UUID.randomUUID().toString()
                : payload.getClientMsgId().trim();

        // 1. Redis 快速幂等拦截
        String idempKey = RedisKeys.msgIdempotency(senderId, clientMsgId);
        ServerAckPayload cachedAck = readCachedAck(idempKey);
        if (cachedAck != null) {
            log.info("命中 Redis 幂等缓存，跳过重复落库: senderId={}, clientMsgId={}, seq={}",
                    senderId, clientMsgId, cachedAck.getSeq());
            return cachedAck;
        }

        // 2. 事务内落库 + 数据库唯一索引兜底并发重传
        ImMessage savedMessage;
        try {
            savedMessage = transactionTemplate.execute(status -> {
                Long seq = seqGeneratorService.nextSeq(convId);
                ImMessage message = new ImMessage();
                message.setConversationId(convId);
                message.setSenderId(senderId);
                message.setClientMsgId(clientMsgId);
                message.setSeq(seq);
                message.setMsgType(payload.getMsgType() == null ? "TEXT" : payload.getMsgType());
                message.setContent(payload.getContent());
                message.setReplyToMsgId(payload.getReplyToMsgId());
                message.setStatus(1);
                messageMapper.insert(message);

                String preview = payload.getContent().length() > 60
                        ? payload.getContent().substring(0, 60) + "..."
                        : payload.getContent();
                conversationMapper.updateLastMessage(convId, seq, message.getId(), preview);
                return message;
            });
        } catch (DuplicateKeyException e) {
            // 极端并发场景：两条相同 clientMsgId 同时穿过 Redis，由 MySQL uk_sender_client_msg 兜底拦截
            log.warn("触发数据库唯一索引幂等兜底: senderId={}, clientMsgId={}", senderId, clientMsgId);
            ImMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<ImMessage>()
                    .eq(ImMessage::getSenderId, senderId)
                    .eq(ImMessage::getClientMsgId, clientMsgId));
            if (existing == null) {
                throw e;
            }
            ServerAckPayload existingAck = ServerAckPayload.builder()
                    .clientMsgId(existing.getClientMsgId())
                    .msgId(existing.getId())
                    .conversationId(existing.getConversationId())
                    .seq(existing.getSeq())
                    .build();
            cacheAck(idempKey, existingAck);
            return existingAck;
        }

        if (savedMessage == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "消息落库失败");
        }

        ServerAckPayload ack = ServerAckPayload.builder()
                .clientMsgId(clientMsgId)
                .msgId(savedMessage.getId())
                .conversationId(convId)
                .seq(savedMessage.getSeq())
                .build();

        // 3. 事务已提交：写入幂等缓存、递增未读数并跨节点推送
        cacheAck(idempKey, ack);

        List<Long> memberIds = conversationService.listMemberIds(convId);
        unreadService.incrementUnreadForMembers(convId, senderId, memberIds);
        // 发送者本人已读到本条消息
        unreadService.markAsRead(senderId, convId, savedMessage.getSeq());

        ImUser sender = userMapper.selectById(senderId);
        String senderNickname = sender != null ? sender.getNickname() : ("用户" + senderId);

        ChatPushPayload pushPayload = ChatPushPayload.builder()
                .msgId(savedMessage.getId())
                .conversationId(convId)
                .senderId(senderId)
                .senderNickname(senderNickname)
                .clientMsgId(clientMsgId)
                .seq(savedMessage.getSeq())
                .msgType(savedMessage.getMsgType())
                .content(savedMessage.getContent())
                .replyToMsgId(savedMessage.getReplyToMsgId())
                .createdAt(System.currentTimeMillis())
                .build();

        dispatchPushToMembers(memberIds, WsPacket.of(WsPacketType.CHAT_PUSH, pushPayload));
        return ack;
    }

    private ServerAckPayload readCachedAck(String idempKey) {
        String json = redisTemplate.opsForValue().get(idempKey);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ServerAckPayload.class);
        } catch (Exception e) {
            log.warn("反序列化幂等缓存失败: key={}", idempKey, e);
            return null;
        }
    }

    private void cacheAck(String idempKey, ServerAckPayload ack) {
        try {
            redisTemplate.opsForValue().set(idempKey, objectMapper.writeValueAsString(ack), IDEMPOTENCY_TTL);
        } catch (Exception e) {
            log.warn("写入幂等缓存失败: key={}", idempKey, e);
        }
    }

    private List<MessageVO> toMessageVOs(List<ImMessage> messages) {
        List<Long> senderIds = messages.stream().map(ImMessage::getSenderId).distinct().toList();
        Map<Long, String> nicknameMap = userMapper.selectBatchIds(senderIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        return messages.stream().map(m -> MessageVO.builder()
                .id(m.getId())
                .conversationId(m.getConversationId())
                .senderId(m.getSenderId())
                .senderNickname(nicknameMap.getOrDefault(m.getSenderId(), "用户" + m.getSenderId()))
                .clientMsgId(m.getClientMsgId())
                .seq(m.getSeq())
                .msgType(m.getMsgType())
                .content(m.getContent())
                .replyToMsgId(m.getReplyToMsgId())
                .createdAt(m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build()).toList();
    }

    private void dispatchPushToMembers(List<Long> memberIds, WsPacket<?> packet) {
        if (memberIds.isEmpty()) {
            return;
        }
        List<String> routeKeys = memberIds.stream().map(RedisKeys::userRoute).toList();
        List<String> nodeIds = redisTemplate.opsForValue().multiGet(routeKeys);
        if (nodeIds == null) {
            return;
        }

        Map<String, List<Long>> nodeToUsers = new HashMap<>();
        for (int i = 0; i < memberIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            if (nodeId != null && !nodeId.isBlank()) {
                nodeToUsers.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(memberIds.get(i));
            }
        }

        nodeToUsers.forEach((nodeId, userIds) -> {
            GatewayPushEvent event = GatewayPushEvent.builder()
                    .targetNodeId(nodeId)
                    .targetUserIds(userIds)
                    .packet(packet)
                    .build();
            rabbitTemplate.convertAndSend(MqConstants.PUSH_EXCHANGE, nodeId, event);
        });
    }
}
