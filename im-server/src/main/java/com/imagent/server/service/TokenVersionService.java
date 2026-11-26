package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * JWT 令牌版本号与账号状态实时校验服务：
 * 改密或封号时递增 token_version 并刷新 Redis，使旧 7 天 JWT 毫秒级失效。
 */
@Service
@RequiredArgsConstructor
public class TokenVersionService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final ImUserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    public void assertValidToken(Long userId, int tokenVersion) {
        String key = RedisKeys.userTokenVersion(userId);
        String cached = redisTemplate.opsForValue().get(key);
        int currentVersion;
        int status;

        if (cached != null && cached.contains(":")) {
            String[] parts = cached.split(":", 2);
            currentVersion = Integer.parseInt(parts[0]);
            status = Integer.parseInt(parts[1]);
        } else {
            ImUser user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已被删除");
            }
            currentVersion = user.getTokenVersion() == null ? 1 : user.getTokenVersion();
            status = user.getStatus() == null ? 0 : user.getStatus();
            cacheState(userId, currentVersion, status);
        }

        if (status != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已被禁用，请重新确认账号状态");
        }
        if (tokenVersion != currentVersion) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已失效（密码变更或已下线），请重新登录");
        }
    }

    public void cacheState(Long userId, int tokenVersion, int status) {
        redisTemplate.opsForValue().set(
                RedisKeys.userTokenVersion(userId),
                tokenVersion + ":" + status,
                CACHE_TTL
        );
    }
}
