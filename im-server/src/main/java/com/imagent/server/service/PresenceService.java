package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.dto.PresenceSettingsVO;
import com.imagent.server.dto.UpdatePresenceSettingsRequest;
import com.imagent.server.entity.ImUser;
import com.imagent.server.entity.ImUserPresenceRule;
import com.imagent.server.mapper.ImUserMapper;
import com.imagent.server.mapper.ImUserPresenceRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线状态与隐身可见性隐私引擎：
 * - ALL   : 开启在线状态（所有人可见）
 * - EXCEPT: 不给谁看（除黑名单中的指定好友外，其余人可见）
 * - ONLY  : 部分可见（仅白名单中的指定好友可见，其余人均隐身）
 * - NONE  : 全员隐身（关闭在线状态，对所有人显示离线）
 *
 * 当处于对会话内成员的隐身状态时，后端自动抑制自动已读回执与正在输入提示，仅在用户主动触发“手动标为已读”时上报已读水位。
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    public static final String MODE_ALL = "ALL";
    public static final String MODE_EXCEPT = "EXCEPT";
    public static final String MODE_ONLY = "ONLY";
    public static final String MODE_NONE = "NONE";

    private static final Set<String> VALID_MODES = Set.of(MODE_ALL, MODE_EXCEPT, MODE_ONLY, MODE_NONE);

    private final ImUserMapper userMapper;
    private final ImUserPresenceRuleMapper ruleMapper;
    private final StringRedisTemplate redisTemplate;

    public record UserPresencePolicy(String mode, Set<Long> exceptUserIds, Set<Long> onlyUserIds) {
        public boolean isStealthTo(Long selfId, Long viewerId) {
            if (viewerId == null || viewerId.equals(selfId) || Long.valueOf(AgentBridgeService.AI_ASSISTANT_USER_ID).equals(viewerId)) {
                return false;
            }
            return switch (mode) {
                case MODE_NONE -> true;
                case MODE_EXCEPT -> exceptUserIds.contains(viewerId);
                case MODE_ONLY -> !onlyUserIds.contains(viewerId);
                default -> false;
            };
        }

        public boolean isStealthInMembers(Long selfId, Collection<Long> memberIds) {
            if (MODE_ALL.equals(mode)) {
                return false;
            }
            if (MODE_NONE.equals(mode)) {
                return true;
            }
            if (memberIds == null || memberIds.isEmpty()) {
                return false;
            }
            for (Long memberId : memberIds) {
                if (isStealthTo(selfId, memberId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public PresenceSettingsVO getPresenceSettings(Long userId) {
        UserPresencePolicy policy = loadPolicy(userId);
        return PresenceSettingsVO.builder()
                .mode(policy.mode())
                .exceptUserIds(policy.exceptUserIds().stream().map(String::valueOf).toList())
                .onlyUserIds(policy.onlyUserIds().stream().map(String::valueOf).toList())
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public PresenceSettingsVO updatePresenceSettings(Long userId, UpdatePresenceSettingsRequest req) {
        String mode = normalizeMode(req.getMode());
        if (!VALID_MODES.contains(mode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的在线状态模式");
        }

        ImUser update = new ImUser();
        update.setId(userId);
        update.setPresenceMode(mode);
        userMapper.updateById(update);

        if (req.getExceptUserIds() != null) {
            replaceRules(userId, MODE_EXCEPT, req.getExceptUserIds());
        }
        if (req.getOnlyUserIds() != null) {
            replaceRules(userId, MODE_ONLY, req.getOnlyUserIds());
        }
        return getPresenceSettings(userId);
    }

    public UserPresencePolicy loadPolicy(Long userId) {
        ImUser user = userMapper.selectById(userId);
        String mode = normalizeMode(user != null ? user.getPresenceMode() : MODE_ALL);
        List<ImUserPresenceRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<ImUserPresenceRule>().eq(ImUserPresenceRule::getUserId, userId));
        Set<Long> exceptIds = new LinkedHashSet<>();
        Set<Long> onlyIds = new LinkedHashSet<>();
        for (ImUserPresenceRule r : rules) {
            if (MODE_EXCEPT.equals(r.getRuleType())) {
                exceptIds.add(r.getTargetUserId());
            } else if (MODE_ONLY.equals(r.getRuleType())) {
                onlyIds.add(r.getTargetUserId());
            }
        }
        return new UserPresencePolicy(mode, exceptIds, onlyIds);
    }

    /**
     * 批量解析目标用户对指定观察者 viewerId 是否“可见在线”（结合 Redis 真实连接 + 隐私规则）
     */
    public Map<Long, Boolean> resolveVisibleOnlineMap(Long viewerId, Collection<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> uniqueIds = targetUserIds.stream().distinct().toList();
        List<String> keys = uniqueIds.stream().map(RedisKeys::userRoute).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);

        Map<Long, Boolean> result = new HashMap<>(uniqueIds.size());
        List<Long> actuallyOnlineOthers = new ArrayList<>();

        for (int i = 0; i < uniqueIds.size(); i++) {
            Long uid = uniqueIds.get(i);
            String route = (values != null && i < values.size()) ? values.get(i) : null;
            boolean connected = route != null && !route.isBlank();
            if (!connected) {
                result.put(uid, false);
            } else if (uid.equals(viewerId)) {
                result.put(uid, true);
            } else {
                result.put(uid, true);
                actuallyOnlineOthers.add(uid);
            }
        }

        if (actuallyOnlineOthers.isEmpty()) {
            return result;
        }

        List<ImUser> users = userMapper.selectBatchIds(actuallyOnlineOthers);
        List<Long> exceptCheckIds = new ArrayList<>();
        List<Long> onlyCheckIds = new ArrayList<>();

        for (ImUser u : users) {
            String mode = normalizeMode(u.getPresenceMode());
            switch (mode) {
                case MODE_NONE -> result.put(u.getId(), false);
                case MODE_EXCEPT -> exceptCheckIds.add(u.getId());
                case MODE_ONLY -> onlyCheckIds.add(u.getId());
                default -> result.put(u.getId(), true);
            }
        }

        if (!exceptCheckIds.isEmpty() && viewerId != null) {
            Set<Long> hidingFromViewer = ruleMapper.selectList(
                            new LambdaQueryWrapper<ImUserPresenceRule>()
                                    .in(ImUserPresenceRule::getUserId, exceptCheckIds)
                                    .eq(ImUserPresenceRule::getRuleType, MODE_EXCEPT)
                                    .eq(ImUserPresenceRule::getTargetUserId, viewerId))
                    .stream()
                    .map(ImUserPresenceRule::getUserId)
                    .collect(Collectors.toSet());
            for (Long uid : hidingFromViewer) {
                result.put(uid, false);
            }
        }

        if (!onlyCheckIds.isEmpty()) {
            Set<Long> allowingViewer = viewerId == null
                    ? Collections.emptySet()
                    : ruleMapper.selectList(
                            new LambdaQueryWrapper<ImUserPresenceRule>()
                                    .in(ImUserPresenceRule::getUserId, onlyCheckIds)
                                    .eq(ImUserPresenceRule::getRuleType, MODE_ONLY)
                                    .eq(ImUserPresenceRule::getTargetUserId, viewerId))
                    .stream()
                    .map(ImUserPresenceRule::getUserId)
                    .collect(Collectors.toSet());
            for (Long uid : onlyCheckIds) {
                result.put(uid, allowingViewer.contains(uid));
            }
        }

        return result;
    }

    public boolean isOnlineVisibleTo(Long targetUserId, Long viewerId) {
        if (targetUserId == null) {
            return false;
        }
        return resolveVisibleOnlineMap(viewerId, List.of(targetUserId)).getOrDefault(targetUserId, false);
    }

    /**
     * 判断 actorUserId 在指定会话成员集合中是否处于隐身态（用于抑制自动已读回执）
     */
    public boolean isStealthInConversation(Long actorUserId, Collection<Long> memberIds) {
        if (actorUserId == null) {
            return false;
        }
        UserPresencePolicy policy = loadPolicy(actorUserId);
        return policy.isStealthInMembers(actorUserId, memberIds);
    }

    /**
     * 过滤掉对 actorUserId 而言处于隐身对象的接收者（用于抑制 TYPING 正在输入提示泄露）
     */
    public List<Long> filterVisibleReceivers(Long actorUserId, List<Long> candidateUserIds) {
        if (actorUserId == null || candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        UserPresencePolicy policy = loadPolicy(actorUserId);
        if (MODE_ALL.equals(policy.mode())) {
            return candidateUserIds;
        }
        if (MODE_NONE.equals(policy.mode())) {
            return Collections.emptyList();
        }
        return candidateUserIds.stream()
                .filter(uid -> !policy.isStealthTo(actorUserId, uid))
                .toList();
    }

    private void replaceRules(Long userId, String ruleType, List<Long> targetUserIds) {
        ruleMapper.delete(new LambdaQueryWrapper<ImUserPresenceRule>()
                .eq(ImUserPresenceRule::getUserId, userId)
                .eq(ImUserPresenceRule::getRuleType, ruleType));
        Set<Long> cleanIds = new HashSet<>();
        for (Long tid : targetUserIds) {
            if (tid != null && !tid.equals(userId) && !Long.valueOf(AgentBridgeService.AI_ASSISTANT_USER_ID).equals(tid)) {
                cleanIds.add(tid);
            }
        }
        for (Long tid : cleanIds) {
            ImUserPresenceRule row = new ImUserPresenceRule();
            row.setUserId(userId);
            row.setRuleType(ruleType);
            row.setTargetUserId(tid);
            ruleMapper.insert(row);
        }
    }

    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_ALL;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
