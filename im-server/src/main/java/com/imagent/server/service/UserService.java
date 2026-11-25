package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.LoginRequest;
import com.imagent.server.dto.RegisterRequest;
import com.imagent.server.dto.UpdateProfileRequest;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Duration PROFILE_CACHE_TTL = Duration.ofMinutes(30);

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
        user.setSignature("");
        user.setBio("");
        user.setStatusEmoji("");
        user.setStatusText("");
        user.setUserType(1);
        user.setStatus(1);
        userMapper.insert(user);

        String token = jwtTokenUtil.generateToken(user.getId(), user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .user(toVO(user, true))
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
                .user(toVO(user, true))
                .build();
    }

    public UserProfileVO getProfile(Long userId) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        String route = redisTemplate.opsForValue().get(RedisKeys.userRoute(userId));
        boolean online = route != null && !route.isBlank();
        return toVO(user, online);
    }

    /**
     * 更新个人资料（头像、昵称、个性签名、个人简介、自定义状态），并同步刷新 Redis 热路径缓存
     */
    public UserProfileVO updateProfile(Long userId, UpdateProfileRequest req) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        ImUser update = new ImUser();
        update.setId(userId);
        if (req.getNickname() != null) {
            String nick = req.getNickname().trim();
            if (nick.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "昵称不能为空");
            }
            update.setNickname(nick);
            user.setNickname(nick);
        }
        if (req.getAvatar() != null) {
            String avatar = req.getAvatar().trim();
            update.setAvatar(avatar);
            user.setAvatar(avatar);
        }
        if (req.getSignature() != null) {
            String sig = req.getSignature().trim();
            update.setSignature(sig);
            user.setSignature(sig);
        }
        if (req.getBio() != null) {
            String bio = req.getBio().trim();
            update.setBio(bio);
            user.setBio(bio);
        }
        if (req.getStatusEmoji() != null) {
            String emo = req.getStatusEmoji().trim();
            update.setStatusEmoji(emo);
            user.setStatusEmoji(emo);
        }
        if (req.getStatusText() != null) {
            String st = req.getStatusText().trim();
            update.setStatusText(st);
            user.setStatusText(st);
        }

        userMapper.updateById(update);

        // Cache-Aside 一致性：同步刷新 Redis 昵称与头像热缓存
        if (req.getNickname() != null) {
            redisTemplate.opsForValue().set(RedisKeys.userNickname(userId), user.getNickname(), PROFILE_CACHE_TTL);
        }
        if (req.getAvatar() != null) {
            redisTemplate.opsForValue().set(RedisKeys.userAvatar(userId), Objects.requireNonNullElse(user.getAvatar(), ""), PROFILE_CACHE_TTL);
        }
        return toVO(user, true);
    }

    /**
     * 修改登录密码（校验原 BCrypt 密码后安全写入新哈希）
     */
    public void changePassword(Long userId, ChangePasswordRequest req) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        ImUser update = new ImUser();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userMapper.updateById(update);
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
        redisTemplate.opsForValue().set(key, nickname, PROFILE_CACHE_TTL);
        return nickname;
    }

    /**
     * 热路径头像查询（Redis Cache-Aside）
     */
    public String getAvatarCached(Long userId) {
        String key = RedisKeys.userAvatar(userId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }
        ImUser user = userMapper.selectById(userId);
        String avatar = (user != null && user.getAvatar() != null) ? user.getAvatar() : "";
        redisTemplate.opsForValue().set(key, avatar, PROFILE_CACHE_TTL);
        return avatar;
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
        return userMapper.selectList(wrapper).stream().map(u -> toVO(u, null)).toList();
    }

    private UserProfileVO toVO(ImUser user, Boolean online) {
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(Objects.requireNonNullElse(user.getAvatar(), ""))
                .signature(Objects.requireNonNullElse(user.getSignature(), ""))
                .bio(Objects.requireNonNullElse(user.getBio(), ""))
                .statusEmoji(Objects.requireNonNullElse(user.getStatusEmoji(), ""))
                .statusText(Objects.requireNonNullElse(user.getStatusText(), ""))
                .online(online)
                .userType(user.getUserType())
                .build();
    }
}
