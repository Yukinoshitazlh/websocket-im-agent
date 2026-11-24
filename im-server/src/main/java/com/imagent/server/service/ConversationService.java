package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.dto.ConversationVO;
import com.imagent.server.dto.CreateGroupConvRequest;
import com.imagent.server.entity.ImConversation;
import com.imagent.server.entity.ImConversationMember;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImConversationMemberMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ImConversationMapper conversationMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImUserMapper userMapper;

    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createOrGetDirectConversation(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能和自己创建单聊");
        }
        ImUser targetUser = userMapper.selectById(targetUserId);
        if (targetUser == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标用户不存在");
        }

        long minUid = Math.min(currentUserId, targetUserId);
        long maxUid = Math.max(currentUserId, targetUserId);
        String bizKey = "direct:" + minUid + ":" + maxUid;

        ImConversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<ImConversation>()
                .eq(ImConversation::getBizKey, bizKey));
        if (existing != null) {
            return toVO(existing, targetUser.getNickname());
        }

        ImConversation conv = new ImConversation();
        conv.setType(1);
        conv.setBizKey(bizKey);
        conv.setTitle("");
        conv.setCurrentSeq(0L);
        conv.setLastMsgPreview("");
        conversationMapper.insert(conv);

        addMember(conv.getId(), currentUserId, 1);
        addMember(conv.getId(), targetUserId, 1);
        return toVO(conv, targetUser.getNickname());
    }

    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createGroupConversation(Long ownerId, CreateGroupConvRequest req) {
        Set<Long> uniqueMembers = new LinkedHashSet<>(req.getMemberIds());
        uniqueMembers.remove(ownerId);
        if (uniqueMembers.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一名其他成员建群");
        }
        List<ImUser> existingUsers = userMapper.selectBatchIds(uniqueMembers);
        if (existingUsers.size() != uniqueMembers.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部分群成员用户不存在");
        }

        ImConversation conv = new ImConversation();
        conv.setType(2);
        conv.setTitle(req.getTitle().trim());
        conv.setOwnerId(ownerId);
        conv.setCurrentSeq(0L);
        conv.setLastMsgPreview("");
        conversationMapper.insert(conv);

        addMember(conv.getId(), ownerId, 3);
        for (Long memberId : uniqueMembers) {
            addMember(conv.getId(), memberId, 1);
        }
        return toVO(conv, conv.getTitle());
    }

    public List<ConversationVO> listMyConversations(Long userId) {
        List<ImConversationMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>().eq(ImConversationMember::getUserId, userId));
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> convIds = memberships.stream().map(ImConversationMember::getConversationId).toList();
        List<ImConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<ImConversation>()
                        .in(ImConversation::getId, convIds)
                        .orderByDesc(ImConversation::getLastMsgTime)
                        .orderByDesc(ImConversation::getCreatedAt));

        List<Long> directConvIds = conversations.stream()
                .filter(c -> Integer.valueOf(1).equals(c.getType()))
                .map(ImConversation::getId)
                .toList();

        Map<Long, String> directTitleMap = resolveDirectTitles(directConvIds, userId);
        List<ConversationVO> result = new ArrayList<>(conversations.size());
        for (ImConversation conv : conversations) {
            String displayTitle = Integer.valueOf(1).equals(conv.getType())
                    ? directTitleMap.getOrDefault(conv.getId(), "单聊会话")
                    : conv.getTitle();
            result.add(toVO(conv, displayTitle));
        }
        return result;
    }

    public boolean isMember(Long conversationId, Long userId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<ImConversationMember>()
                .eq(ImConversationMember::getConversationId, conversationId)
                .eq(ImConversationMember::getUserId, userId)) > 0;
    }

    public List<Long> listMemberIds(Long conversationId) {
        return memberMapper.selectList(new LambdaQueryWrapper<ImConversationMember>()
                        .eq(ImConversationMember::getConversationId, conversationId))
                .stream()
                .map(ImConversationMember::getUserId)
                .toList();
    }

    private Map<Long, String> resolveDirectTitles(List<Long> directConvIds, Long currentUserId) {
        if (directConvIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ImConversationMember> peerMembers = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>()
                        .in(ImConversationMember::getConversationId, directConvIds)
                        .ne(ImConversationMember::getUserId, currentUserId));
        if (peerMembers.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> peerIds = peerMembers.stream().map(ImConversationMember::getUserId).distinct().toList();
        Map<Long, String> userNicknameMap = userMapper.selectBatchIds(peerIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        return peerMembers.stream().collect(Collectors.toMap(
                ImConversationMember::getConversationId,
                m -> userNicknameMap.getOrDefault(m.getUserId(), "用户" + m.getUserId()),
                (a, b) -> a
        ));
    }

    private void addMember(Long convId, Long userId, int role) {
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(convId);
        member.setUserId(userId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(0);
        memberMapper.insert(member);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle) {
        Long lastTime = conv.getLastMsgTime() != null
                ? conv.getLastMsgTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        return ConversationVO.builder()
                .id(conv.getId())
                .type(conv.getType())
                .title(displayTitle)
                .currentSeq(conv.getCurrentSeq())
                .lastMsgPreview(conv.getLastMsgPreview())
                .lastMsgTime(lastTime)
                .build();
    }
}
