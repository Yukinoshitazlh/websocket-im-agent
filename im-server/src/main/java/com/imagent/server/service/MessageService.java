package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.mq.GatewayPushEvent;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.ChatPushPayload;
import com.imagent.common.protocol.payload.ChatSendPayload;
import com.imagent.common.protocol.payload.MsgRecallPayload;
import com.imagent.common.protocol.payload.ReadReceiptPayload;
import com.imagent.common.protocol.payload.ServerAckPayload;
import com.imagent.common.protocol.payload.TypingPayload;
import com.imagent.server.dto.InternalAgentReplyRequest;
import com.imagent.server.dto.MessageVO;
import com.imagent.server.dto.ReactionGroupVO;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);
    private static final long RECALL_WINDOW_SECONDS = 120L;
    private static final DateTimeFormatter REMIND_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ImMessageMapper messageMapper;
    private final ImConversationMapper conversationMapper;
    private final ImUserMapper userMapper;
    private final UserService userService;
    private final ConversationService conversationService;
    private final SeqGeneratorService seqGeneratorService;
    private final UnreadService unreadService;
    private final AgentBridgeService agentBridgeService;
    private final RateLimitService rateLimitService;
    private final MessageCollabService collabService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Executor pushDispatchExecutor;

    public MessageService(ImMessageMapper messageMapper,
                          ImConversationMapper conversationMapper,
                          ImUserMapper userMapper,
                          UserService userService,
                          ConversationService conversationService,
                          SeqGeneratorService seqGeneratorService,
                          UnreadService unreadService,
                          AgentBridgeService agentBridgeService,
                          RateLimitService rateLimitService,
                          MessageCollabService collabService,
                          StringRedisTemplate redisTemplate,
                          RabbitTemplate rabbitTemplate,
                          TransactionTemplate transactionTemplate,
                          ObjectMapper objectMapper,
                          @Qualifier("pushDispatchExecutor") Executor pushDispatchExecutor) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.conversationService = conversationService;
        this.seqGeneratorService = seqGeneratorService;
        this.unreadService = unreadService;
        this.agentBridgeService = agentBridgeService;
        this.rateLimitService = rateLimitService;
        this.collabService = collabService;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.pushDispatchExecutor = pushDispatchExecutor;
    }

    /**
     * 游标分页拉取历史消息：基于 uk_conv_seq (conversation_id, seq) 走索引范围扫描，避免 OFFSET 深分页
     */
    public List<MessageVO> listHistory(Long userId, Long conversationId, Long beforeSeq, int limit) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        LambdaQueryWrapper<ImMessage> wrapper = new LambdaQueryWrapper<ImMessage>()
                .eq(ImMessage::getConversationId, conversationId)
                .lt(beforeSeq != null && beforeSeq > 0, ImMessage::getSeq, beforeSeq)
                .orderByDesc(ImMessage::getSeq)
                .last("LIMIT " + safeLimit);
        List<ImMessage> messages = messageMapper.selectList(wrapper);
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(messages);
        return toMessageVOs(messages, userId);
    }

    /**
     * 会话内历史关键词搜索（强校验会话成员权限，跳过已撤回消息）
     */
    public List<MessageVO> searchMessages(Long userId, Long conversationId, String keyword, int limit) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<ImMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ImMessage>()
                        .eq(ImMessage::getConversationId, conversationId)
                        .eq(ImMessage::getStatus, 1)
                        .like(ImMessage::getContent, keyword.trim())
                        .orderByDesc(ImMessage::getSeq)
                        .last("LIMIT " + safeLimit));
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        return toMessageVOs(messages, userId);
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
        return toMessageVOs(messages, userId);
    }

    /**
     * 处理客户端已读回执（MSG_ACK 或 HTTP 已读上报），水位前进时向其他成员实时广播 READ_RECEIPT
     */
    public void ackRead(Long userId, Long conversationId, Long seq) {
        List<Long> memberIds = conversationService.listMemberIds(conversationId);
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该会话已读状态");
        }
        boolean advanced = unreadService.markAsRead(userId, conversationId, seq);
        if (advanced && seq != null && seq > 0) {
            List<Long> others = memberIds.stream().filter(id -> !id.equals(userId)).toList();
            if (!others.isEmpty()) {
                ReadReceiptPayload receipt = ReadReceiptPayload.builder()
                        .conversationId(conversationId)
                        .userId(userId)
                        .lastReadSeq(seq)
                        .build();
                pushDispatchExecutor.execute(() ->
                        dispatchPushToMembers(others, WsPacket.of(WsPacketType.READ_RECEIPT, receipt)));
            }
        }
    }

    /**
     * 消息撤回：校验发送者本人 + 2 分钟时间窗，更新 status=2 并全会话广播 MSG_RECALL 信令
     */
    public void recallMessage(Long userId, Long msgId) {
        ImMessage msg = messageMapper.selectById(msgId);
        if (msg == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        if (!userId.equals(msg.getSenderId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能撤回自己发送的消息");
        }
        if (Integer.valueOf(2).equals(msg.getStatus())) {
            return;
        }
        if (msg.getCreatedAt() != null
                && Duration.between(msg.getCreatedAt(), LocalDateTime.now()).toSeconds() > RECALL_WINDOW_SECONDS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "发送超过 2 分钟的消息不可撤回");
        }

        ImMessage update = new ImMessage();
        update.setId(msgId);
        update.setStatus(2);
        update.setContent("已撤回一条消息");
        messageMapper.updateById(update);

        String operatorNickname = userService.getNicknameCached(userId);
        List<Long> memberIds = conversationService.listMemberIds(msg.getConversationId());
        MsgRecallPayload recallPayload = MsgRecallPayload.builder()
                .conversationId(msg.getConversationId())
                .msgId(msgId)
                .seq(msg.getSeq())
                .operatorId(userId)
                .operatorNickname(operatorNickname)
                .build();

        pushDispatchExecutor.execute(() ->
                dispatchPushToMembers(memberIds, WsPacket.of(WsPacketType.MSG_RECALL, recallPayload)));
    }

    /**
     * 正在输入瞬时信令（TYPING）：零 DB 写入、零 seq 分配、仅查 Redis 缓存并向会话其他在线成员内存推送
     */
    public void broadcastTyping(Long userId, Long conversationId, boolean typing) {
        List<Long> memberIds = conversationService.listMemberIds(conversationId);
        if (!memberIds.contains(userId)) {
            return;
        }
        List<Long> targets = memberIds.stream().filter(id -> !id.equals(userId)).toList();
        if (targets.isEmpty()) {
            return;
        }
        String nickname = userService.getNicknameCached(userId);
        TypingPayload payload = TypingPayload.builder()
                .conversationId(conversationId)
                .userId(userId)
                .nickname(nickname)
                .typing(typing)
                .build();
        pushDispatchExecutor.execute(() ->
                dispatchPushToMembers(targets, WsPacket.of(WsPacketType.TYPING, payload)));
    }

    /**
     * 发布群系统灰条通知（拉人/踢人/改群名/退群）
     */
    public void publishSystemMessage(Long convId, Long operatorId, String content) {
        String clientMsgId = "sys-" + UUID.randomUUID().toString().substring(0, 12);
        ImMessage saved = transactionTemplate.execute(status -> {
            Long seq = seqGeneratorService.nextSeq(convId);
            ImMessage msg = new ImMessage();
            msg.setConversationId(convId);
            msg.setSenderId(operatorId);
            msg.setClientMsgId(clientMsgId);
            msg.setSeq(seq);
            msg.setMsgType("SYSTEM");
            msg.setContent(content);
            msg.setStatus(1);
            messageMapper.insert(msg);
            conversationMapper.updateLastMessage(convId, seq, msg.getId(), "[系统] " + content);
            return msg;
        });
        if (saved == null) {
            return;
        }
        List<Long> memberIds = conversationService.listMemberIds(convId);
        ChatPushPayload pushPayload = ChatPushPayload.builder()
                .msgId(saved.getId())
                .conversationId(convId)
                .senderId(operatorId)
                .senderNickname("系统通知")
                .clientMsgId(clientMsgId)
                .seq(saved.getSeq())
                .msgType("SYSTEM")
                .content(content)
                .status(1)
                .createdAt(System.currentTimeMillis())
                .build();
        pushDispatchExecutor.execute(() ->
                dispatchPushToMembers(memberIds, WsPacket.of(WsPacketType.CHAT_PUSH, pushPayload)));
    }

    /**
     * 可靠消息受理：
     * 1. Redis 幂等缓存拦截弱网重传；
     * 2. TransactionTemplate 事务内分配并发安全 seq 并落库（MySQL 唯一索引 uk_sender_client_msg 兜底并发重传）；
     * 3. 事务提交后卸载至平台线程池异步递增未读数、写入 @ 提醒并投递 RabbitMQ。
     */
    public ServerAckPayload processIncomingMessage(Long senderId, ChatSendPayload payload) {
        Long convId = payload.getConversationId();
        if (convId == null || payload.getContent() == null || payload.getContent().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话ID和消息内容不能为空");
        }
        List<Long> memberIds = conversationService.listMemberIds(convId);
        if (!memberIds.contains(senderId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权向该会话发送消息");
        }

        String clientMsgId = (payload.getClientMsgId() == null || payload.getClientMsgId().isBlank())
                ? UUID.randomUUID().toString()
                : payload.getClientMsgId().trim();

        String idempKey = RedisKeys.msgIdempotency(senderId, clientMsgId);
        ServerAckPayload cachedAck = readCachedAck(idempKey);
        if (cachedAck != null) {
            log.info("命中 Redis 幂等缓存，跳过重复落库: senderId={}, clientMsgId={}, seq={}",
                    senderId, clientMsgId, cachedAck.getSeq());
            return cachedAck;
        }

        rateLimitService.checkChatSendAllowed(senderId);

        String msgType = (payload.getMsgType() == null || payload.getMsgType().isBlank())
                ? "TEXT" : payload.getMsgType().trim();
        boolean triggerAgent = "TEXT".equals(msgType)
                && agentBridgeService.shouldTriggerAgent(payload.getMentionAgent(), payload.getContent());
        if (triggerAgent) {
            rateLimitService.checkAgentTriggerAllowed(senderId);
        }

        String replyToSender = null;
        String replyToContent = null;
        if (payload.getReplyToMsgId() != null) {
            ImMessage target = messageMapper.selectById(payload.getReplyToMsgId());
            if (target != null && convId.equals(target.getConversationId())) {
                replyToSender = userService.getNicknameCached(target.getSenderId());
                replyToContent = Integer.valueOf(2).equals(target.getStatus())
                        ? "已撤回一条消息"
                        : buildPreview(target.getMsgType(), target.getContent());
            }
        }

        String mergedExtra = mergeAtUsersIntoExtra(payload.getExtra(), payload.getAtUserIds());
        String preview = buildPreview(msgType, payload.getContent());
        ImMessage savedMessage;
        try {
            savedMessage = transactionTemplate.execute(status -> {
                Long seq = seqGeneratorService.nextSeq(convId);
                ImMessage message = new ImMessage();
                message.setConversationId(convId);
                message.setSenderId(senderId);
                message.setClientMsgId(clientMsgId);
                message.setSeq(seq);
                message.setMsgType(msgType);
                message.setContent(payload.getContent());
                message.setReplyToMsgId(payload.getReplyToMsgId());
                message.setExtra(mergedExtra);
                message.setStatus(1);
                messageMapper.insert(message);

                conversationMapper.updateLastMessage(convId, seq, message.getId(), preview);
                return message;
            });
        } catch (DuplicateKeyException e) {
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

        cacheAck(idempKey, ack);

        String senderNickname = userService.getNicknameCached(senderId);
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
                .replyToSender(replyToSender)
                .replyToContent(replyToContent)
                .extra(savedMessage.getExtra())
                .atUserIds(payload.getAtUserIds())
                .status(1)
                .createdAt(System.currentTimeMillis())
                .build();

        List<String> atUserIds = payload.getAtUserIds();
        pushDispatchExecutor.execute(() -> {
            try {
                unreadService.incrementUnreadForMembers(convId, senderId, memberIds);
                unreadService.markMentioned(convId, senderId, savedMessage.getSeq(), atUserIds, memberIds);
                unreadService.clearSenderUnreadFast(senderId, convId);
                dispatchPushToMembers(memberIds, WsPacket.of(WsPacketType.CHAT_PUSH, pushPayload));
                if (triggerAgent) {
                    agentBridgeService.enqueueAgentTask(convId, senderId, savedMessage.getId(), savedMessage.getContent());
                }
            } catch (Exception e) {
                log.error("异步推送/后处理消息失败: msgId={}, convId={}", savedMessage.getId(), convId, e);
            }
        });
        return ack;
    }

    /**
     * 供 im-agent 在流式生成结束（或被打断截断）后统一分配 seq 并持久化最终回复
     */
    public ServerAckPayload persistAgentReply(InternalAgentReplyRequest req) {
        Long convId = req.getConversationId();
        String clientMsgId = "agent-task-" + req.getTaskId();
        ImMessage saved = transactionTemplate.execute(status -> {
            Long seq = seqGeneratorService.nextSeq(convId);
            ImMessage message = new ImMessage();
            message.setConversationId(convId);
            message.setSenderId(AgentBridgeService.AI_ASSISTANT_USER_ID);
            message.setClientMsgId(clientMsgId);
            message.setSeq(seq);
            message.setMsgType(req.getMsgType() == null ? "AGENT_TEXT" : req.getMsgType());
            message.setContent(req.getContent());
            message.setReplyToMsgId(req.getReplyToMsgId());
            message.setStatus(1);
            messageMapper.insert(message);

            String preview = "[AI助手] " + (req.getContent().length() > 50
                    ? req.getContent().substring(0, 50) + "..."
                    : req.getContent());
            conversationMapper.updateLastMessage(convId, seq, message.getId(), preview);
            return message;
        });

        if (saved == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Agent 回复落库失败");
        }
        List<Long> memberIds = conversationService.listMemberIds(convId);
        unreadService.incrementUnreadForMembers(convId, AgentBridgeService.AI_ASSISTANT_USER_ID, memberIds);

        return ServerAckPayload.builder()
                .clientMsgId(clientMsgId)
                .msgId(saved.getId())
                .conversationId(convId)
                .seq(saved.getSeq())
                .build();
    }

    /**
     * 供 Redis ZSet 延迟队列调度器调用：到期自动落库并向全会话实时推送日程提醒卡片
     */
    public void publishScheduledReminder(ImGroupReminder reminder) {
        Long convId = reminder.getConversationId();
        String clientMsgId = "reminder-fire-" + reminder.getId();
        String timeStr = reminder.getRemindAt() != null ? reminder.getRemindAt().format(REMIND_FMT) : "";
        String content = String.format("【日程提醒到点通知】%s\n%s\n计划时间：%s",
                reminder.getTitle(), reminder.getContent(), timeStr);

        ImMessage saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Long seq = seqGeneratorService.nextSeq(convId);
                ImMessage message = new ImMessage();
                message.setConversationId(convId);
                message.setSenderId(AgentBridgeService.AI_ASSISTANT_USER_ID);
                message.setClientMsgId(clientMsgId);
                message.setSeq(seq);
                message.setMsgType("REMINDER");
                message.setContent(content);
                message.setStatus(1);
                messageMapper.insert(message);

                conversationMapper.updateLastMessage(convId, seq, message.getId(), "[日程提醒] " + reminder.getTitle());
                return message;
            });
        } catch (DuplicateKeyException e) {
            log.info("日程提醒消息已推送过，跳过重复落库: reminderId={}", reminder.getId());
            return;
        }

        if (saved == null) {
            return;
        }

        List<Long> memberIds = conversationService.listMemberIds(convId);
        ChatPushPayload pushPayload = ChatPushPayload.builder()
                .msgId(saved.getId())
                .conversationId(convId)
                .senderId(AgentBridgeService.AI_ASSISTANT_USER_ID)
                .senderNickname("AI 助手")
                .clientMsgId(clientMsgId)
                .seq(saved.getSeq())
                .msgType("REMINDER")
                .content(content)
                .status(1)
                .createdAt(System.currentTimeMillis())
                .build();

        pushDispatchExecutor.execute(() -> {
            unreadService.incrementUnreadForMembers(convId, AgentBridgeService.AI_ASSISTANT_USER_ID, memberIds);
            dispatchPushToMembers(memberIds, WsPacket.of(WsPacketType.CHAT_PUSH, pushPayload));
        });
    }

    private String buildPreview(String msgType, String content) {
        if ("IMAGE".equals(msgType)) {
            return "[图片]";
        }
        if ("FILE".equals(msgType)) {
            return "[文件] " + (content.length() > 40 ? content.substring(0, 40) : content);
        }
        if ("FORWARD_BUNDLE".equals(msgType)) {
            return "[聊天记录] " + (content.length() > 36 ? content.substring(0, 36) : content);
        }
        return content.length() > 60 ? content.substring(0, 60) + "..." : content;
    }

    private String mergeAtUsersIntoExtra(String extra, List<String> atUserIds) {
        if (atUserIds == null || atUserIds.isEmpty()) {
            return extra;
        }
        try {
            ObjectNode root;
            if (extra != null && !extra.isBlank()) {
                JsonNode parsed = objectMapper.readTree(extra);
                root = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
            } else {
                root = objectMapper.createObjectNode();
            }
            ArrayNode arr = objectMapper.createArrayNode();
            atUserIds.forEach(arr::add);
            root.set("atUserIds", arr);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return extra;
        }
    }

    private List<String> extractAtUserIds(String extra) {
        if (extra == null || extra.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(extra);
            JsonNode arr = root.get("atUserIds");
            if (arr != null && arr.isArray()) {
                List<String> list = new ArrayList<>(arr.size());
                arr.forEach(n -> list.add(n.asText()));
                return list;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
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

    private List<MessageVO> toMessageVOs(List<ImMessage> messages, Long currentUserId) {
        Set<Long> userIds = new HashSet<>();
        List<Long> replyIds = new ArrayList<>();
        List<Long> msgIds = new ArrayList<>(messages.size());
        for (ImMessage m : messages) {
            msgIds.add(m.getId());
            userIds.add(m.getSenderId());
            if (m.getReplyToMsgId() != null) {
                replyIds.add(m.getReplyToMsgId());
            }
        }

        Map<Long, ImMessage> replyMap = replyIds.isEmpty()
                ? Collections.emptyMap()
                : messageMapper.selectBatchIds(replyIds).stream()
                .collect(Collectors.toMap(ImMessage::getId, m -> m, (a, b) -> a));
        replyMap.values().forEach(r -> userIds.add(r.getSenderId()));

        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        Map<Long, List<ReactionGroupVO>> reactionMap = collabService.loadReactionsMap(msgIds, currentUserId);

        return messages.stream().map(m -> {
            ImMessage replied = m.getReplyToMsgId() != null ? replyMap.get(m.getReplyToMsgId()) : null;
            String replySender = replied != null
                    ? nicknameMap.getOrDefault(replied.getSenderId(), "用户" + replied.getSenderId())
                    : null;
            String replyContent = replied != null
                    ? (Integer.valueOf(2).equals(replied.getStatus()) ? "已撤回一条消息" : buildPreview(replied.getMsgType(), replied.getContent()))
                    : null;
            return MessageVO.builder()
                    .id(m.getId())
                    .conversationId(m.getConversationId())
                    .senderId(m.getSenderId())
                    .senderNickname(nicknameMap.getOrDefault(m.getSenderId(), "用户" + m.getSenderId()))
                    .clientMsgId(m.getClientMsgId())
                    .seq(m.getSeq())
                    .msgType(m.getMsgType())
                    .content(Integer.valueOf(2).equals(m.getStatus()) ? "已撤回一条消息" : m.getContent())
                    .replyToMsgId(m.getReplyToMsgId())
                    .replyToSender(replySender)
                    .replyToContent(replyContent)
                    .extra(m.getExtra())
                    .atUserIds(extractAtUserIds(m.getExtra()))
                    .reactions(reactionMap.getOrDefault(m.getId(), Collections.emptyList()))
                    .status(Objects.requireNonNullElse(m.getStatus(), 1))
                    .createdAt(m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .build();
        }).toList();
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
