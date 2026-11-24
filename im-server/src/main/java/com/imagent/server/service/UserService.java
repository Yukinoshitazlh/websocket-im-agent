package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.LoginRequest;
import com.imagent.server.dto.RegisterRequest;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Duration NICKNAME_CACHE_TTL = Duration.ofMinutes(30);

    private final ImUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenUtil jwtTokenUtil;
    private final StringRedisTemplate redisTemplate;

    public AuthResponse register(RegisterRequest req) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getUsername, req.getUsername().trim()));
        if (count > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "账号已存在");
        }

        ImUser user = new ImUser();
        user.setUsername(req.getUsername().trim());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname().trim());
        user.setAvatar("");
        user.setUserType(1);
        user.setStatus(1);
        userMapper.insert(user);

        String token = jwtTokenUtil.generateToken(user.getId(), user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .user(toVO(user))
                .build();
    }

    public AuthResponse login(LoginRequest req) {
        ImUser user = userMapper.selectOne(new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getUsername, req.getUsername().trim()));
        if (user == null || !Integer.valueOf(1).equals(user.getUserType())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }

        String token = jwtTokenUtil.generateToken(user.getId(), user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .user(toVO(user))
                .build();
    }

    public UserProfileVO getProfile(Long userId) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return toVO(user);
    }

    /**
     * 热路径昵称查询（Redis Cache-Aside，避免每条消息回源查 im_user 表）
     */
    public String getNicknameCached(Long userId) {
        String key = RedisKeys.userNickname(userId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }
        ImUser user = userMapper.selectById(userId);
        String nickname = (user != null && user.getNickname() != null) ? user.getNickname() : ("用户" + userId);
        redisTemplate.opsForValue().set(key, nickname, NICKNAME_CACHE_TTL);
        return nickname;
    }

    public List<UserProfileVO> searchUsers(String keyword, Long excludeUserId) {
        LambdaQueryWrapper<ImUser> wrapper = new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getStatus, 1)
                .eq(ImUser::getUserType, 1)
                .ne(excludeUserId != null, ImUser::getId, excludeUserId)
                .last("LIMIT 20");
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(ImUser::getUsername, trimmed).or().like(ImUser::getNickname, trimmed));
        }
        return userMapper.selectList(wrapper).stream().map(this::toVO).toList();
    }

    private UserProfileVO toVO(ImUser user) {
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .userType(user.getUserType())
                .build();
    }
}
