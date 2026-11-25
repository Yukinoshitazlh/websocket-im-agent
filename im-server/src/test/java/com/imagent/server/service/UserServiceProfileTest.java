package com.imagent.server.service;

import com.imagent.common.model.BizException;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.UpdateProfileRequest;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

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

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, passwordEncoder, jwtTokenUtil, redisTemplate);
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
}
