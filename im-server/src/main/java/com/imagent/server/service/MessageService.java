package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ImMessageMapper messageMapper;
    private final ImConversationMapper conversationMapper;
    private final ImUserMapper userMapper;
    private final ConversationService conversationService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

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

    @Transactional(rollbackFor = Exception.class)
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
                : payload.getClientMsgId();

        Long seq = allocateNextSeq(convId);
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

        ImUser sender = userMapper.selectById(senderId);
        String senderNickname = sender != null ? sender.getNickname() : ("用户" + senderId);

        ChatPushPayload pushPayload = ChatPushPayload.builder()
                .msgId(message.getId())
                .conversationId(convId)
                .senderId(senderId)
                .senderNickname(senderNickname)
                .seq(seq)
                .msgType(message.getMsgType())
                .content(message.getContent())
                .replyToMsgId(message.getReplyToMsgId())
                .createdAt(System.currentTimeMillis())
                .build();

        dispatchPushToMembers(convId, WsPacket.of(WsPacketType.CHAT_PUSH, pushPayload));

        return ServerAckPayload.builder()
                .clientMsgId(clientMsgId)
                .msgId(message.getId())
                .conversationId(convId)
                .seq(seq)
                .build();
    }

    private Long allocateNextSeq(Long convId) {
        String seqKey = RedisKeys.conversationSeq(convId);
        if (Boolean.FALSE.equals(redisTemplate.hasKey(seqKey))) {
            Long maxSeq = messageMapper.selectMaxSeq(convId);
            redisTemplate.opsForValue().setIfAbsent(seqKey, String.valueOf(maxSeq == null ? 0L : maxSeq));
        }
        return redisTemplate.opsForValue().increment(seqKey);
    }

    private void dispatchPushToMembers(Long convId, WsPacket<?> packet) {
        List<Long> memberIds = conversationService.listMemberIds(convId);
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
