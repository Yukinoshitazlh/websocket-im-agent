package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.LoginRequest;
import com.imagent.server.dto.RegisterRequest;
import com.imagent.server.dto.SendEmailCodeVO;
import com.imagent.server.dto.UpdateEmailRequest;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Duration PROFILE_CACHE_TTL = Duration.ofMinutes(30);

    private final ImUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenUtil jwtTokenUtil;
    private final StringRedisTemplate redisTemplate;
    private final AuthSecurityService authSecurityService;
    private final TokenVersionService tokenVersionService;
    private final MessagePushHelper messagePushHelper;
    private final ServerSecurityProperties securityProperties;

    public AuthResponse register(RegisterRequest req, String clientIp) {
        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        authSecurityService.checkIpRateLimit("register", clientIp, auth.getIpRegisterMaxPerHour(), Duration.ofHours(1));
        authSecurityService.verifyInviteCodeIfRequired(req.getInviteCode());
        authSecurityService.assertPasswordStrength(req.getPassword());

        String email = authSecurityService.normalizeEmail(req.getEmail());
        if (auth.isRequireEmailCode()) {
            authSecurityService.verifyEmailCode(email, req.getEmailCode());
        } else if (auth.isRequireCaptcha()) {
            authSecurityService.verifyCaptcha(req.getCaptchaId(), req.getCaptchaCode());
        }

        Long emailCount = userMapper.selectCount(new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getEmail, email));
        if (emailCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该邮箱已注册");
        }

        String username = resolveUniqueUsername(req.getUsername(), email);
        String nickname = (req.getNickname() == null || req.getNickname().isBlank())
                ? username
                : req.getNickname().trim();

        ImUser user = new ImUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setNickname(nickname);
        user.setAvatar("");
        user.setSignature("");
        user.setBio("");
        user.setStatusEmoji("");
        user.setStatusText("");
        user.setUserType(1);
        user.setStatus(1);
        user.setTokenVersion(1);
        userMapper.insert(user);

        tokenVersionService.cacheState(user.getId(), 1, 1);
        String token = jwtTokenUtil.generateToken(user.getId(), user.getUsername(), 1);
        return AuthResponse.builder()
                .token(token)
                .user(toVO(user, true))
                .build();
    }

    public AuthResponse login(LoginRequest req, String clientIp) {
        String account = req.resolveLoginAccount();
        if (account.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入邮箱或用户名");
        }

        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        authSecurityService.checkIpRateLimit("login", clientIp, auth.getIpLoginMaxPerMinute(), Duration.ofMinutes(1));
        authSecurityService.checkLoginNotLocked(account);
        authSecurityService.verifyCaptcha(req.getCaptchaId(), req.getCaptchaCode());

        ImUser user = account.contains("@")
                ? userMapper.selectOne(new LambdaQueryWrapper<ImUser>().eq(ImUser::getEmail, authSecurityService.normalizeEmail(account)))
                : userMapper.selectOne(new LambdaQueryWrapper<ImUser>().eq(ImUser::getUsername, account));

        if (user == null || !Integer.valueOf(1).equals(user.getUserType())) {
            authSecurityService.recordLoginFailure(account);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            authSecurityService.recordLoginFailure(account);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }

        authSecurityService.clearLoginFailures(account);
        // 重复登录单点会话控制：递增 token_version 立即作废旧端 JWT，并主动通知旧网关踢下线旧连接
        int nextVersion = (user.getTokenVersion() == null ? 1 : user.getTokenVersion()) + 1;
        ImUser versionUpdate = new ImUser();
        versionUpdate.setId(user.getId());
        versionUpdate.setTokenVersion(nextVersion);
        userMapper.updateById(versionUpdate);
        user.setTokenVersion(nextVersion);

        tokenVersionService.cacheState(user.getId(), nextVersion, 1);

        // 同步向旧网关节点投递 FORCE_LOGOUT，确保在新端建连覆盖路由前立即踢出旧连接
        WsPacket<Map<String, Object>> forceLogout = WsPacket.of(
                WsPacketType.FORCE_LOGOUT,
                Map.of("reason", "LOGIN_ELSEWHERE", "message", "你的账号已在其他设备或页面登录，当前会话已下线")
        );
        try {
            messagePushHelper.pushSync(List.of(user.getId()), forceLogout);
        } catch (Exception ignored) {
            // 旧连接踢出投递失败不阻断本次正常登录，旧 JWT 已因 token_version 递增彻底失效
        }

        String token = jwtTokenUtil.generateToken(user.getId(), user.getUsername(), nextVersion);
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

        if (req.getNickname() != null) {
            redisTemplate.opsForValue().set(RedisKeys.userNickname(userId), user.getNickname(), PROFILE_CACHE_TTL);
        }
        if (req.getAvatar() != null) {
            redisTemplate.opsForValue().set(RedisKeys.userAvatar(userId), Objects.requireNonNullElse(user.getAvatar(), ""), PROFILE_CACHE_TTL);
        }
        return toVO(user, true);
    }

    /**
     * 已登录用户发送绑定/更换邮箱验证码（前置校验邮箱未被其他账号占用）
     */
    public SendEmailCodeVO sendBindEmailCode(Long userId, String rawEmail, String clientIp) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        String email = authSecurityService.normalizeEmail(rawEmail);
        if (email.equalsIgnoreCase(Objects.requireNonNullElse(user.getEmail(), ""))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新邮箱与当前已绑定邮箱相同");
        }
        Long occupied = userMapper.selectCount(new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getEmail, email)
                .ne(ImUser::getId, userId));
        if (occupied > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该邮箱已被其他账号绑定");
        }
        return authSecurityService.sendBindEmailCode(email, clientIp);
    }

    /**
     * 添加或更换绑定邮箱（校验当前登录密码 + 邮箱验证码 + 邮箱唯一性）
     */
    public UserProfileVO updateEmail(Long userId, UpdateEmailRequest req) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前登录密码不正确");
        }

        String email = authSecurityService.normalizeEmail(req.getEmail());
        if (email.equalsIgnoreCase(Objects.requireNonNullElse(user.getEmail(), ""))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新邮箱与当前已绑定邮箱相同");
        }
        Long occupied = userMapper.selectCount(new LambdaQueryWrapper<ImUser>()
                .eq(ImUser::getEmail, email)
                .ne(ImUser::getId, userId));
        if (occupied > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该邮箱已被其他账号绑定");
        }

        if (securityProperties.getAuth().isRequireEmailCode()) {
            authSecurityService.verifyEmailCode(email, req.getEmailCode());
        }

        ImUser update = new ImUser();
        update.setId(userId);
        update.setEmail(email);
        userMapper.updateById(update);
        user.setEmail(email);

        return toVO(user, true);
    }

    /**
     * 修改登录密码：校验原密码与新密码强度，递增 token_version 立即作废全部旧 JWT，
     * 通过网关踢下线旧 WebSocket 连接，并向当前会话签发全新 JWT。
     */
    public AuthResponse changePassword(Long userId, ChangePasswordRequest req) {
        ImUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        authSecurityService.assertPasswordStrength(req.getNewPassword());

        int nextVersion = (user.getTokenVersion() == null ? 1 : user.getTokenVersion()) + 1;
        ImUser update = new ImUser();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        update.setTokenVersion(nextVersion);
        userMapper.updateById(update);

        user.setTokenVersion(nextVersion);
        int status = user.getStatus() == null ? 1 : user.getStatus();
        tokenVersionService.cacheState(userId, nextVersion, status);

        // 通知网关断开该用户所有旧 WebSocket 连接
        WsPacket<Map<String, Object>> forceLogout = WsPacket.of(
                WsPacketType.FORCE_LOGOUT,
                Map.of("reason", "PASSWORD_CHANGED", "message", "账号密码已修改，请重新建立安全连接")
        );
        messagePushHelper.pushAsync(List.of(userId), forceLogout);

        String refreshedToken = jwtTokenUtil.generateToken(userId, user.getUsername(), nextVersion);
        return AuthResponse.builder()
                .token(refreshedToken)
                .user(toVO(user, true))
                .build();
    }

    /**
     * 热路径昵称查询（Redis Cache-Aside）
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

    private String resolveUniqueUsername(String rawUsername, String email) {
        if (rawUsername != null && !rawUsername.isBlank()) {
            String trimmed = rawUsername.trim();
            if (!trimmed.matches("^[a-zA-Z0-9_.-]{3,32}$")) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用户名仅支持 3~32 位字母、数字、下划线、点或短横线");
            }
            Long exists = userMapper.selectCount(new LambdaQueryWrapper<ImUser>().eq(ImUser::getUsername, trimmed));
            if (exists > 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用户名已被占用");
            }
            return trimmed;
        }

        // 未填用户名时，基于邮箱前缀自动生成合法唯一用户名
        String prefix = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (prefix.length() < 3) {
            prefix = "u_" + prefix;
        }
        if (prefix.length() > 24) {
            prefix = prefix.substring(0, 24);
        }
        String candidate = prefix;
        for (int i = 0; i < 5; i++) {
            Long exists = userMapper.selectCount(new LambdaQueryWrapper<ImUser>().eq(ImUser::getUsername, candidate));
            if (exists == 0) {
                return candidate;
            }
            candidate = prefix + "_" + UUID.randomUUID().toString().substring(0, 4);
        }
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private UserProfileVO toVO(ImUser user, Boolean online) {
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
                .build();
    }
}
