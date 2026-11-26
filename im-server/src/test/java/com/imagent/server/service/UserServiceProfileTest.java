package com.imagent.server.service;

import com.imagent.common.model.BizException;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.LoginRequest;
import com.imagent.server.dto.UpdateProfileRequest;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceProfileTest {

    @Mock private ImUserMapper userMapper;
    @Mock private JwtTokenUtil jwtTokenUtil;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AuthSecurityService authSecurityService;
    @Mock private TokenVersionService tokenVersionService;
    @Mock private MessagePushHelper messagePushHelper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final ServerSecurityProperties securityProperties = new ServerSecurityProperties();
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userMapper,
                passwordEncoder,
                jwtTokenUtil,
                redisTemplate,
                authSecurityService,
                tokenVersionService,
                messagePushHelper,
                securityProperties
        );
    }

    @Test
    void shouldUpdateProfileAndRefreshRedisCaches() {
        ImUser user = new ImUser();
        user.setId(3001L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setAvatar("");
        user.setUserType(1);

        when(userMapper.selectById(3001L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setNickname("Alice Pro");
        req.setAvatar("/uploads/avatar.png");
        req.setSignature("代码如诗");
        req.setBio("Java 后端开发");
        req.setStatusEmoji("💻");
        req.setStatusText("专注编码中");

        UserProfileVO vo = userService.updateProfile(3001L, req);

        assertEquals("Alice Pro", vo.getNickname());
        assertEquals("/uploads/avatar.png", vo.getAvatar());
        assertEquals("代码如诗", vo.getSignature());
        assertEquals("💻", vo.getStatusEmoji());
        verify(userMapper).updateById(any(ImUser.class));
        verify(valueOps).set(eq("im:user:nick:3001"), eq("Alice Pro"), any(Duration.class));
        verify(valueOps).set(eq("im:user:avatar:3001"), eq("/uploads/avatar.png"), any(Duration.class));
    }

    @Test
    void shouldRejectWrongOldPassword() {
        ImUser user = new ImUser();
        user.setId(3002L);
        user.setPasswordHash(passwordEncoder.encode("correct123"));
        when(userMapper.selectById(3002L)).thenReturn(user);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("wrong123");
        req.setNewPassword("newpass456");

        assertThrows(BizException.class, () -> userService.changePassword(3002L, req));
    }

    @Test
    void shouldBumpTokenVersionAndForceLogoutOnPasswordChange() {
        ImUser user = new ImUser();
        user.setId(3003L);
        user.setUsername("alice");
        user.setStatus(1);
        user.setUserType(1);
        user.setTokenVersion(2);
        user.setPasswordHash(passwordEncoder.encode("OldPass123"));

        when(userMapper.selectById(3003L)).thenReturn(user);
        when(jwtTokenUtil.generateToken(3003L, "alice", 3)).thenReturn("new-jwt-v3");

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("OldPass123");
        req.setNewPassword("NewPass456");

        AuthResponse resp = userService.changePassword(3003L, req);

        assertEquals("new-jwt-v3", resp.getToken());
        verify(tokenVersionService).cacheState(3003L, 3, 1);

        ArgumentCaptor<WsPacket<?>> packetCaptor = ArgumentCaptor.forClass(WsPacket.class);
        verify(messagePushHelper).pushAsync(eq(List.of(3003L)), packetCaptor.capture());
        assertEquals(WsPacketType.FORCE_LOGOUT, packetCaptor.getValue().getType());
    }

    @Test
    void shouldBumpTokenVersionAndKickOldSessionOnDuplicateLogin() {
        ImUser user = new ImUser();
        user.setId(3004L);
        user.setUsername("bob");
        user.setEmail("bob@example.com");
        user.setStatus(1);
        user.setUserType(1);
        user.setTokenVersion(1);
        user.setPasswordHash(passwordEncoder.encode("Pulse2026"));

        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtTokenUtil.generateToken(3004L, "bob", 2)).thenReturn("jwt-v2");

        LoginRequest req = new LoginRequest();
        req.setAccount("bob@example.com");
        req.setPassword("Pulse2026");

        AuthResponse resp = userService.login(req, "127.0.0.1");

        assertEquals("jwt-v2", resp.getToken());
        verify(userMapper).updateById(any(ImUser.class));
        verify(tokenVersionService).cacheState(3004L, 2, 1);

        ArgumentCaptor<WsPacket<?>> packetCaptor = ArgumentCaptor.forClass(WsPacket.class);
        verify(messagePushHelper).pushSync(eq(List.of(3004L)), packetCaptor.capture());
        assertEquals(WsPacketType.FORCE_LOGOUT, packetCaptor.getValue().getType());
    }
}
