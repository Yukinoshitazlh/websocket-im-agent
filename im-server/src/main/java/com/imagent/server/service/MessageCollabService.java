package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.MsgPinPayload;
import com.imagent.common.protocol.payload.MsgReactionPayload;
import com.imagent.server.dto.PinnedMessageVO;
import com.imagent.server.dto.ReactionGroupVO;
import com.imagent.server.entity.ImConversationPin;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.entity.ImMessageReaction;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationPinMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImMessageReactionMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 飞书式群协同能力服务：
 * 1. 消息表情表态（Emoji Reaction）：唯一索引幂等 Toggle + 批量零 N+1 聚合 + 实时广播；
 * 2. 消息置顶（Pin / 群公告条）：支持群内/单聊重要消息置顶与实时同步。
 */
@Service
@RequiredArgsConstructor
public class MessageCollabService {

    private final ImMessageReactionMapper reactionMapper;
    private final ImConversationPinMapper pinMapper;
    private final ImMessageMapper messageMapper;
    private final ImUserMapper userMapper;
    private final UserService userService;
    private final ConversationService conversationService;
    private final MessagePushHelper pushHelper;

    /**
     * 幂等切换某条消息的表情表态，并向会话所有在线成员广播 MSG_REACTION
     */
    public List<ReactionGroupVO> toggleReaction(Long userId, Long msgId, String rawEmoji) {
        String emoji = rawEmoji == null ? "" : rawEmoji.trim();
        if (emoji.isEmpty() || emoji.length() > 16) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无效的表情符号");
        }
        ImMessage msg = messageMapper.selectById(msgId);
        if (msg == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        Long convId = msg.getConversationId();
        List<Long> memberIds = conversationService.listMemberIds(convId);
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }

        LambdaQueryWrapper<ImMessageReaction> query = new LambdaQueryWrapper<ImMessageReaction>()
                .eq(ImMessageReaction::getMsgId, msgId)
                .eq(ImMessageReaction::getUserId, userId)
                .eq(ImMessageReaction::getEmoji, emoji);
        ImMessageReaction existing = reactionMapper.selectOne(query);

        boolean added;
        if (existing != null) {
            reactionMapper.deleteById(existing.getId());
            added = false;
        } else {
            ImMessageReaction entity = new ImMessageReaction();
            entity.setMsgId(msgId);
            entity.setConversationId(convId);
            entity.setUserId(userId);
            entity.setEmoji(emoji);
            try {
                reactionMapper.insert(entity);
            } catch (DuplicateKeyException ignored) {
                // 并发重复点击时幂等视为已表态
            }
            added = true;
        }

        String nickname = userService.getNicknameCached(userId);
        MsgReactionPayload payload = MsgReactionPayload.builder()
                .conversationId(convId)
                .msgId(msgId)
                .userId(userId)
                .nickname(nickname)
                .emoji(emoji)
                .added(added)
                .build();
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.MSG_REACTION, payload));

        return loadReactionsMap(List.of(msgId), userId).getOrDefault(msgId, Collections.emptyList());
    }

    /**
     * 批量加载消息列表的表情表态（单次 IN 查询 + 批量查昵称，零 N+1）
     */
    public Map<Long, List<ReactionGroupVO>> loadReactionsMap(List<Long> msgIds, Long currentUserId) {
        if (msgIds == null || msgIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ImMessageReaction> rows = reactionMapper.selectList(
                new LambdaQueryWrapper<ImMessageReaction>()
                        .in(ImMessageReaction::getMsgId, msgIds)
                        .orderByAsc(ImMessageReaction::getCreatedAt));
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> userIds = rows.stream().map(ImMessageReaction::getUserId).collect(Collectors.toSet());
        Map<Long, String> nickMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        Map<Long, Map<String, List<ImMessageReaction>>> grouped = new HashMap<>();
        for (ImMessageReaction row : rows) {
            grouped.computeIfAbsent(row.getMsgId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getEmoji(), k -> new ArrayList<>())
                    .add(row);
        }

        Map<Long, List<ReactionGroupVO>> result = new HashMap<>(grouped.size());
        grouped.forEach((msgId, emojiMap) -> {
            List<ReactionGroupVO> groups = new ArrayList<>(emojiMap.size());
            emojiMap.forEach((emoji, list) -> {
                boolean mine = currentUserId != null && list.stream().anyMatch(r -> currentUserId.equals(r.getUserId()));
                List<String> users = list.stream()
                        .map(r -> nickMap.getOrDefault(r.getUserId(), "用户" + r.getUserId()))
                        .toList();
                groups.add(ReactionGroupVO.builder()
                        .emoji(emoji)
                        .count(list.size())
                        .reactedByMe(mine)
                        .users(users)
                        .build());
            });
            result.put(msgId, groups);
        });
        return result;
    }

    /**
     * 置顶一条会话消息（群公告/重要结论置顶条），并实时广播 MSG_PIN
     */
    public PinnedMessageVO pinMessage(Long userId, Long convId, Long msgId) {
        List<Long> memberIds = conversationService.listMemberIds(convId);
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        ImMessage msg = messageMapper.selectById(msgId);
        if (msg == null || !convId.equals(msg.getConversationId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在或不属于该会话");
        }
        if (Integer.valueOf(2).equals(msg.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已撤回的消息不可置顶");
        }

        ImConversationPin pin = new ImConversationPin();
        pin.setConversationId(convId);
        pin.setMsgId(msgId);
        pin.setPinnedBy(userId);
        try {
            pinMapper.insert(pin);
        } catch (DuplicateKeyException ignored) {
            // 已置顶则幂等返回
        }

        String senderNick = userService.getNicknameCached(msg.getSenderId());
        String pinnedByNick = userService.getNicknameCached(userId);
        String preview = buildPinPreview(msg);

        MsgPinPayload payload = MsgPinPayload.builder()
                .conversationId(convId)
                .msgId(msgId)
                .seq(msg.getSeq())
                .senderNickname(senderNick)
                .preview(preview)
                .pinnedByNickname(pinnedByNick)
                .pinned(true)
                .build();
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.MSG_PIN, payload));

        return PinnedMessageVO.builder()
                .msgId(msgId)
                .conversationId(convId)
                .seq(msg.getSeq())
                .senderNickname(senderNick)
                .msgType(msg.getMsgType())
                .preview(preview)
                .pinnedByNickname(pinnedByNick)
                .pinnedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 取消置顶一条会话消息，并实时广播 MSG_PIN
     */
    public void unpinMessage(Long userId, Long convId, Long msgId) {
        List<Long> memberIds = conversationService.listMemberIds(convId);
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        pinMapper.delete(new LambdaQueryWrapper<ImConversationPin>()
                .eq(ImConversationPin::getConversationId, convId)
                .eq(ImConversationPin::getMsgId, msgId));

        MsgPinPayload payload = MsgPinPayload.builder()
                .conversationId(convId)
                .msgId(msgId)
                .pinned(false)
                .pinnedByNickname(userService.getNicknameCached(userId))
                .build();
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.MSG_PIN, payload));
    }

    /**
     * 查询会话所有置顶消息列表（按置顶时间倒序）
     */
    public List<PinnedMessageVO> listPins(Long userId, Long convId) {
        if (!conversationService.isMember(convId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        List<ImConversationPin> pins = pinMapper.selectList(
                new LambdaQueryWrapper<ImConversationPin>()
                        .eq(ImConversationPin::getConversationId, convId)
                        .orderByDesc(ImConversationPin::getPinnedAt));
        if (pins.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> msgIds = pins.stream().map(ImConversationPin::getMsgId).toList();
        Map<Long, ImMessage> msgMap = messageMapper.selectBatchIds(msgIds).stream()
                .collect(Collectors.toMap(ImMessage::getId, m -> m, (a, b) -> a));

        Set<Long> userIds = new HashSet<>();
        pins.forEach(p -> userIds.add(p.getPinnedBy()));
        msgMap.values().forEach(m -> userIds.add(m.getSenderId()));
        Map<Long, String> nickMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        List<PinnedMessageVO> result = new ArrayList<>(pins.size());
        for (ImConversationPin pin : pins) {
            ImMessage m = msgMap.get(pin.getMsgId());
            if (m == null || Integer.valueOf(2).equals(m.getStatus())) {
                continue;
            }
            long pinnedAtMs = pin.getPinnedAt() != null
                    ? pin.getPinnedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : System.currentTimeMillis();
            result.add(PinnedMessageVO.builder()
                    .msgId(m.getId())
                    .conversationId(convId)
                    .seq(m.getSeq())
                    .senderNickname(nickMap.getOrDefault(m.getSenderId(), "用户" + m.getSenderId()))
                    .msgType(m.getMsgType())
                    .preview(buildPinPreview(m))
                    .pinnedByNickname(nickMap.getOrDefault(pin.getPinnedBy(), "成员"))
                    .pinnedAt(pinnedAtMs)
                    .build());
        }
        return result;
    }

    private String buildPinPreview(ImMessage msg) {
        if ("IMAGE".equals(msg.getMsgType())) {
            return "[图片]";
        }
        if ("FILE".equals(msg.getMsgType())) {
            return "[文件] " + msg.getContent();
        }
        if ("FORWARD_BUNDLE".equals(msg.getMsgType())) {
            return "[合并转发] " + msg.getContent();
        }
        String text = msg.getContent() == null ? "" : msg.getContent().replace('\n', ' ');
        return text.length() > 72 ? text.substring(0, 72) + "..." : text;
    }
}
