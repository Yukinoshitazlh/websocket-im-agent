package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.server.dto.FriendRequestVO;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.entity.ImFriendRequest;
import com.imagent.server.entity.ImFriendship;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImFriendRequestMapper;
import com.imagent.server.mapper.ImFriendshipMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 微信式好友与黑名单服务：
 * - 搜账号/邮箱发起好友申请 -> 对方同意双向建立好友关系；
 * - 支持删除好友、拉黑与解除拉黑；
 * - 为单聊建连、群聊拉人与私聊消息投递提供统一关系权限守卫；
 * - 通过 FRIEND_EVENT 实时推送好友申请与同意通知。
 */
@Service
@RequiredArgsConstructor
public class FriendService {

    public static final int STATUS_FRIEND = 1;
    public static final int STATUS_BLOCKED = 2;

    private final ImFriendshipMapper friendshipMapper;
    private final ImFriendRequestMapper friendRequestMapper;
    private final ImUserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final MessagePushHelper messagePushHelper;
    private final PresenceService presenceService;

    /**
     * 我的好友列表（含在线绿点与状态，支持关键词过滤）
     */
    public List<UserProfileVO> listFriends(Long userId, String keyword) {
        List<ImFriendship> edges = friendshipMapper.selectList(
                new LambdaQueryWrapper<ImFriendship>()
                        .eq(ImFriendship::getUserId, userId)
                        .eq(ImFriendship::getStatus, STATUS_FRIEND)
                        .orderByDesc(ImFriendship::getUpdatedAt));
        if (edges.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> friendIds = edges.stream().map(ImFriendship::getFriendId).toList();
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(friendIds).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));
        Map<Long, Boolean> onlineMap = presenceService != null
                ? presenceService.resolveVisibleOnlineMap(userId, friendIds)
                : resolveOnlineFlags(friendIds);

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<UserProfileVO> result = new ArrayList<>();
        for (Long fid : friendIds) {
            ImUser u = userMap.get(fid);
            if (u == null || !Integer.valueOf(1).equals(u.getStatus())) {
                continue;
            }
            if (!kw.isEmpty()) {
                boolean match = containsIgnoreCase(u.getNickname(), kw)
                        || containsIgnoreCase(u.getUsername(), kw)
                        || containsIgnoreCase(u.getEmail(), kw);
                if (!match) {
                    continue;
                }
            }
            result.add(toProfileVO(u, onlineMap.getOrDefault(fid, false), "FRIEND", null));
        }
        // 在线好友优先展示
        result.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.getOnline()), Boolean.TRUE.equals(a.getOnline())));
        return result;
    }

    /**
     * 黑名单列表
     */
    public List<UserProfileVO> listBlockedUsers(Long userId) {
        List<ImFriendship> edges = friendshipMapper.selectList(
                new LambdaQueryWrapper<ImFriendship>()
                        .eq(ImFriendship::getUserId, userId)
                        .eq(ImFriendship::getStatus, STATUS_BLOCKED)
                        .orderByDesc(ImFriendship::getUpdatedAt));
        if (edges.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> targetIds = edges.stream().map(ImFriendship::getFriendId).toList();
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(targetIds).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));

        List<UserProfileVO> result = new ArrayList<>();
        for (Long tid : targetIds) {
            ImUser u = userMap.get(tid);
            if (u != null) {
                result.add(toProfileVO(u, false, "BLOCKED", null));
            }
        }
        return result;
    }

    /**
     * 好友申请列表（包含收到的和发出的，按时间倒序）
     */
    public List<FriendRequestVO> listFriendRequests(Long userId) {
        List<ImFriendRequest> rows = friendRequestMapper.selectList(
                new LambdaQueryWrapper<ImFriendRequest>()
                        .eq(ImFriendRequest::getToUserId, userId)
                        .or()
                        .eq(ImFriendRequest::getFromUserId, userId)
                        .orderByDesc(ImFriendRequest::getCreatedAt)
                        .last("LIMIT 50"));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> peerIds = rows.stream()
                .map(r -> userId.equals(r.getToUserId()) ? r.getFromUserId() : r.getToUserId())
                .collect(Collectors.toSet());
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(peerIds).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));

        List<FriendRequestVO> result = new ArrayList<>(rows.size());
        for (ImFriendRequest req : rows) {
            boolean incoming = userId.equals(req.getToUserId());
            Long peerId = incoming ? req.getFromUserId() : req.getToUserId();
            ImUser peer = userMap.get(peerId);
            long createdMs = req.getCreatedAt() == null
                    ? System.currentTimeMillis()
                    : req.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            result.add(FriendRequestVO.builder()
                    .id(req.getId())
                    .direction(incoming ? "INCOMING" : "OUTGOING")
                    .peerUserId(peerId)
                    .peerUsername(peer != null ? peer.getUsername() : "user" + peerId)
                    .peerNickname(peer != null ? peer.getNickname() : "用户" + peerId)
                    .peerAvatar(peer != null ? Objects.requireNonNullElse(peer.getAvatar(), "") : "")
                    .peerSignature(peer != null ? Objects.requireNonNullElse(peer.getSignature(), "") : "")
                    .remark(Objects.requireNonNullElse(req.getRemark(), ""))
                    .status(req.getStatus())
                    .createdAt(createdMs)
                    .build());
        }
        return result;
    }

    /**
     * 发送好友申请（若对方此前已向我发过 PENDING 申请，则直接双向通过成为好友）
     */
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestVO sendFriendRequest(Long fromUserId, Long targetUserId, String remark) {
        if (fromUserId.equals(targetUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能添加自己为好友");
        }
        ImUser target = userMapper.selectById(targetUserId);
        if (target == null || !Integer.valueOf(1).equals(target.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标用户不存在或不可用");
        }
        if (!Integer.valueOf(1).equals(target.getUserType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "AI 助手无需添加好友，在聊天中输入 @AI 即可唤醒");
        }

        if (isBlockedBy(fromUserId, targetUserId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "对方已拒绝接收你的好友申请");
        }
        if (isBlockedBy(targetUserId, fromUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "你已拉黑该用户，请先在黑名单中解除拉黑");
        }
        if (isMutualFriend(fromUserId, targetUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "你们已经是好友了");
        }

        // 若对方正好已发来待处理申请，直接自动通过
        ImFriendRequest reversePending = friendRequestMapper.selectOne(
                new LambdaQueryWrapper<ImFriendRequest>()
                        .eq(ImFriendRequest::getFromUserId, targetUserId)
                        .eq(ImFriendRequest::getToUserId, fromUserId)
                        .eq(ImFriendRequest::getStatus, "PENDING")
                        .last("LIMIT 1"));
        if (reversePending != null) {
            acceptFriendRequest(fromUserId, reversePending.getId());
            return FriendRequestVO.builder()
                    .id(reversePending.getId())
                    .direction("OUTGOING")
                    .peerUserId(targetUserId)
                    .peerUsername(target.getUsername())
                    .peerNickname(target.getNickname())
                    .peerAvatar(Objects.requireNonNullElse(target.getAvatar(), ""))
                    .remark("双方互相申请，已自动成为好友")
                    .status("ACCEPTED")
                    .createdAt(System.currentTimeMillis())
                    .build();
        }

        // 防重复提交 PENDING 申请：若已有未处理申请则刷新附言
        ImFriendRequest existingPending = friendRequestMapper.selectOne(
                new LambdaQueryWrapper<ImFriendRequest>()
                        .eq(ImFriendRequest::getFromUserId, fromUserId)
                        .eq(ImFriendRequest::getToUserId, targetUserId)
                        .eq(ImFriendRequest::getStatus, "PENDING")
                        .last("LIMIT 1"));

        String safeRemark = remark == null ? "" : remark.trim();
        ImFriendRequest req;
        if (existingPending != null) {
            existingPending.setRemark(safeRemark);
            friendRequestMapper.updateById(existingPending);
            req = existingPending;
        } else {
            req = new ImFriendRequest();
            req.setFromUserId(fromUserId);
            req.setToUserId(targetUserId);
            req.setRemark(safeRemark);
            req.setStatus("PENDING");
            friendRequestMapper.insert(req);
        }

        ImUser sender = userMapper.selectById(fromUserId);
        String senderNick = sender != null ? sender.getNickname() : ("用户" + fromUserId);
        pushFriendEvent(targetUserId, "REQUEST_RECEIVED", fromUserId, senderNick, safeRemark);

        return FriendRequestVO.builder()
                .id(req.getId())
                .direction("OUTGOING")
                .peerUserId(targetUserId)
                .peerUsername(target.getUsername())
                .peerNickname(target.getNickname())
                .peerAvatar(Objects.requireNonNullElse(target.getAvatar(), ""))
                .peerSignature(Objects.requireNonNullElse(target.getSignature(), ""))
                .remark(safeRemark)
                .status("PENDING")
                .createdAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 同意好友申请（双向建立好友关系，并实时通知申请人）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long acceptFriendRequest(Long userId, Long requestId) {
        ImFriendRequest req = friendRequestMapper.selectById(requestId);
        if (req == null || !userId.equals(req.getToUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "好友申请不存在");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该好友申请已被处理");
        }

        Long peerId = req.getFromUserId();
        req.setStatus("ACCEPTED");
        req.setHandledAt(LocalDateTime.now());
        friendRequestMapper.updateById(req);

        upsertFriendship(userId, peerId, STATUS_FRIEND);
        upsertFriendship(peerId, userId, STATUS_FRIEND);

        ImUser acceptor = userMapper.selectById(userId);
        String acceptorNick = acceptor != null ? acceptor.getNickname() : ("用户" + userId);
        pushFriendEvent(peerId, "REQUEST_ACCEPTED", userId, acceptorNick, "已同意你的好友申请，现在可以开始聊天了");
        return peerId;
    }

    /**
     * 拒绝好友申请
     */
    public void rejectFriendRequest(Long userId, Long requestId) {
        ImFriendRequest req = friendRequestMapper.selectById(requestId);
        if (req == null || !userId.equals(req.getToUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "好友申请不存在");
        }
        if (!"PENDING".equals(req.getStatus())) {
            return;
        }
        req.setStatus("REJECTED");
        req.setHandledAt(LocalDateTime.now());
        friendRequestMapper.updateById(req);
    }

    /**
     * 删除好友（解除双向好友关系，历史记录保留但禁止继续私聊）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFriend(Long userId, Long friendId) {
        friendshipMapper.delete(new LambdaQueryWrapper<ImFriendship>()
                .eq(ImFriendship::getUserId, userId)
                .eq(ImFriendship::getFriendId, friendId)
                .eq(ImFriendship::getStatus, STATUS_FRIEND));
        friendshipMapper.delete(new LambdaQueryWrapper<ImFriendship>()
                .eq(ImFriendship::getUserId, friendId)
                .eq(ImFriendship::getFriendId, userId)
                .eq(ImFriendship::getStatus, STATUS_FRIEND));
        pushFriendEvent(friendId, "FRIEND_REMOVED", userId, "", "");
    }

    /**
     * 拉黑用户（置本人侧为 BLOCKED，移除对方侧好友关系并拒绝待处理申请）
     */
    @Transactional(rollbackFor = Exception.class)
    public void blockUser(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能拉黑自己");
        }
        upsertFriendship(userId, targetUserId, STATUS_BLOCKED);
        friendshipMapper.delete(new LambdaQueryWrapper<ImFriendship>()
                .eq(ImFriendship::getUserId, targetUserId)
                .eq(ImFriendship::getFriendId, userId)
                .eq(ImFriendship::getStatus, STATUS_FRIEND));

        List<ImFriendRequest> pending = friendRequestMapper.selectList(
                new LambdaQueryWrapper<ImFriendRequest>()
                        .eq(ImFriendRequest::getStatus, "PENDING")
                        .and(w -> w
                                .nested(a -> a.eq(ImFriendRequest::getFromUserId, userId).eq(ImFriendRequest::getToUserId, targetUserId))
                                .or()
                                .nested(b -> b.eq(ImFriendRequest::getFromUserId, targetUserId).eq(ImFriendRequest::getToUserId, userId))));
        for (ImFriendRequest r : pending) {
            r.setStatus("REJECTED");
            r.setHandledAt(LocalDateTime.now());
            friendRequestMapper.updateById(r);
        }
        pushFriendEvent(targetUserId, "FRIEND_REMOVED", userId, "", "");
    }

    /**
     * 解除拉黑
     */
    public void unblockUser(Long userId, Long targetUserId) {
        friendshipMapper.delete(new LambdaQueryWrapper<ImFriendship>()
                .eq(ImFriendship::getUserId, userId)
                .eq(ImFriendship::getFriendId, targetUserId)
                .eq(ImFriendship::getStatus, STATUS_BLOCKED));
    }

    /**
     * 校验双方是否可以发起单聊或互发私聊消息
     */
    public void assertCanDirectChat(Long senderId, Long peerId) {
        if (peerId == null || senderId.equals(peerId)) {
            return;
        }
        ImFriendship myEdge = findEdge(senderId, peerId);
        ImFriendship peerEdge = findEdge(peerId, senderId);

        if (myEdge != null && Integer.valueOf(STATUS_BLOCKED).equals(myEdge.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "你已将对方加入黑名单，请先解除拉黑");
        }
        if (peerEdge != null && Integer.valueOf(STATUS_BLOCKED).equals(peerEdge.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "消息已发出，但被对方拒收");
        }
        boolean mutualFriend = myEdge != null && Integer.valueOf(STATUS_FRIEND).equals(myEdge.getStatus())
                && peerEdge != null && Integer.valueOf(STATUS_FRIEND).equals(peerEdge.getStatus());
        if (!mutualFriend) {
            throw new BizException(ErrorCode.FORBIDDEN, "对方还不是你的好友，请先发送好友申请");
        }
    }

    /**
     * 校验建群/拉人时的目标成员均为操作者好友
     */
    public void assertAllFriends(Long operatorId, Collection<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        List<Long> checkIds = targetIds.stream()
                .filter(id -> id != null && !id.equals(operatorId))
                .distinct()
                .toList();
        if (checkIds.isEmpty()) {
            return;
        }
        List<ImFriendship> myFriends = friendshipMapper.selectList(
                new LambdaQueryWrapper<ImFriendship>()
                        .eq(ImFriendship::getUserId, operatorId)
                        .eq(ImFriendship::getStatus, STATUS_FRIEND)
                        .in(ImFriendship::getFriendId, checkIds));
        Set<Long> myFriendSet = myFriends.stream().map(ImFriendship::getFriendId).collect(Collectors.toSet());

        List<ImFriendship> reverseFriends = friendshipMapper.selectList(
                new LambdaQueryWrapper<ImFriendship>()
                        .in(ImFriendship::getUserId, checkIds)
                        .eq(ImFriendship::getFriendId, operatorId)
                        .eq(ImFriendship::getStatus, STATUS_FRIEND));
        Set<Long> reverseSet = reverseFriends.stream().map(ImFriendship::getUserId).collect(Collectors.toSet());

        for (Long tid : checkIds) {
            if (!myFriendSet.contains(tid) || !reverseSet.contains(tid)) {
                throw new BizException(ErrorCode.FORBIDDEN, "只能邀请互为好友的联系人加入群聊");
            }
        }
    }

    /**
     * 批量解析当前用户与一组目标用户的关系状态，零 N+1 查询
     */
    public void enrichRelationStatus(Long viewerId, List<UserProfileVO> profiles) {
        if (viewerId == null || profiles == null || profiles.isEmpty()) {
            return;
        }
        List<Long> targetIds = profiles.stream()
                .map(UserProfileVO::getId)
                .filter(id -> id != null && !id.equals(viewerId))
                .distinct()
                .toList();

        Map<Long, Integer> myEdges = new HashMap<>();
        Map<Long, Integer> peerEdges = new HashMap<>();
        Map<Long, Long> incomingPendingMap = new HashMap<>();
        Set<Long> outgoingPendingSet = new java.util.HashSet<>();

        if (!targetIds.isEmpty()) {
            friendshipMapper.selectList(new LambdaQueryWrapper<ImFriendship>()
                            .eq(ImFriendship::getUserId, viewerId)
                            .in(ImFriendship::getFriendId, targetIds))
                    .forEach(e -> myEdges.put(e.getFriendId(), e.getStatus()));

            friendshipMapper.selectList(new LambdaQueryWrapper<ImFriendship>()
                            .in(ImFriendship::getUserId, targetIds)
                            .eq(ImFriendship::getFriendId, viewerId))
                    .forEach(e -> peerEdges.put(e.getUserId(), e.getStatus()));

            friendRequestMapper.selectList(new LambdaQueryWrapper<ImFriendRequest>()
                            .eq(ImFriendRequest::getStatus, "PENDING")
                            .eq(ImFriendRequest::getToUserId, viewerId)
                            .in(ImFriendRequest::getFromUserId, targetIds))
                    .forEach(r -> incomingPendingMap.put(r.getFromUserId(), r.getId()));

            friendRequestMapper.selectList(new LambdaQueryWrapper<ImFriendRequest>()
                            .eq(ImFriendRequest::getStatus, "PENDING")
                            .eq(ImFriendRequest::getFromUserId, viewerId)
                            .in(ImFriendRequest::getToUserId, targetIds))
                    .forEach(r -> outgoingPendingSet.add(r.getToUserId()));
        }

        for (UserProfileVO vo : profiles) {
            if (vo.getId() == null) {
                continue;
            }
            if (viewerId.equals(vo.getId())) {
                vo.setRelationStatus("SELF");
                continue;
            }
            Integer mySt = myEdges.get(vo.getId());
            Integer peerSt = peerEdges.get(vo.getId());
            if (Integer.valueOf(STATUS_BLOCKED).equals(mySt)) {
                vo.setRelationStatus("BLOCKED");
            } else if (Integer.valueOf(STATUS_BLOCKED).equals(peerSt)) {
                vo.setRelationStatus("BLOCKED_BY");
            } else if (Integer.valueOf(STATUS_FRIEND).equals(mySt) && Integer.valueOf(STATUS_FRIEND).equals(peerSt)) {
                vo.setRelationStatus("FRIEND");
            } else if (incomingPendingMap.containsKey(vo.getId())) {
                vo.setRelationStatus("PENDING_IN");
                vo.setPendingRequestId(incomingPendingMap.get(vo.getId()));
            } else if (outgoingPendingSet.contains(vo.getId())) {
                vo.setRelationStatus("PENDING_OUT");
            } else {
                vo.setRelationStatus("NONE");
            }
        }
    }

    public boolean isMutualFriend(Long userA, Long userB) {
        ImFriendship e1 = findEdge(userA, userB);
        ImFriendship e2 = findEdge(userB, userA);
        return e1 != null && Integer.valueOf(STATUS_FRIEND).equals(e1.getStatus())
                && e2 != null && Integer.valueOf(STATUS_FRIEND).equals(e2.getStatus());
    }

    public boolean isBlockedBy(Long userId, Long potentialBlockerId) {
        ImFriendship edge = findEdge(potentialBlockerId, userId);
        return edge != null && Integer.valueOf(STATUS_BLOCKED).equals(edge.getStatus());
    }

    private ImFriendship findEdge(Long userId, Long friendId) {
        return friendshipMapper.selectOne(new LambdaQueryWrapper<ImFriendship>()
                .eq(ImFriendship::getUserId, userId)
                .eq(ImFriendship::getFriendId, friendId)
                .last("LIMIT 1"));
    }

    private void upsertFriendship(Long userId, Long friendId, int status) {
        ImFriendship existing = findEdge(userId, friendId);
        if (existing != null) {
            existing.setStatus(status);
            friendshipMapper.updateById(existing);
        } else {
            ImFriendship edge = new ImFriendship();
            edge.setUserId(userId);
            edge.setFriendId(friendId);
            edge.setStatus(status);
            edge.setRemarkName("");
            friendshipMapper.insert(edge);
        }
    }

    private void pushFriendEvent(Long targetUserId, String eventType, Long fromUserId, String fromNickname, String remark) {
        try {
            Map<String, Object> payload = Map.of(
                    "eventType", eventType,
                    "fromUserId", String.valueOf(fromUserId),
                    "fromNickname", fromNickname == null ? "" : fromNickname,
                    "remark", remark == null ? "" : remark
            );
            messagePushHelper.pushAsync(List.of(targetUserId), WsPacket.of(WsPacketType.FRIEND_EVENT, payload));
        } catch (Exception ignored) {
            // 通知失败不影响主流程
        }
    }

    private Map<Long, Boolean> resolveOnlineFlags(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = userIds.stream().map(RedisKeys::userRoute).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, Boolean> map = new HashMap<>();
        if (values != null) {
            for (int i = 0; i < userIds.size(); i++) {
                String route = values.get(i);
                map.put(userIds.get(i), route != null && !route.isBlank());
            }
        }
        return map;
    }

    private boolean containsIgnoreCase(String value, String kwLower) {
        return value != null && value.toLowerCase().contains(kwLower);
    }

    private UserProfileVO toProfileVO(ImUser user, boolean online, String relationStatus, Long pendingReqId) {
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatar(Objects.requireNonNullElse(user.getAvatar(), ""))
                .signature(Objects.requireNonNullElse(user.getSignature(), ""))
                .bio(Objects.requireNonNullElse(user.getBio(), ""))
                .statusEmoji(Objects.requireNonNullElse(user.getStatusEmoji(), ""))
                .statusText(Objects.requireNonNullElse(user.getStatusText(), ""))
                .online(online)
                .userType(user.getUserType())
                .relationStatus(relationStatus)
                .pendingRequestId(pendingReqId)
                .build();
    }
}
