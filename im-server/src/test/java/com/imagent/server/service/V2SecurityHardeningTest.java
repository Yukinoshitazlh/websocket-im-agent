package com.imagent.server.service;

import com.imagent.common.constant.InternalAuthConstants;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.common.InternalAuthInterceptor;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.config.StorageProperties;
import com.imagent.server.dto.CaptchaVO;
import com.imagent.server.dto.WsTicketVO;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImFileObjectMapper;
import com.imagent.server.mapper.ImUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class V2SecurityHardeningTest {

    @Mock private ImFileObjectMapper fileObjectMapper;
    @Mock private ImUserMapper userMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Test
    void internalAuthInterceptorShouldRejectMissingOrInvalidToken() {
        ServerSecurityProperties props = new ServerSecurityProperties();
        props.setInternalToken("super-secret-internal-token-32bytes!");
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor(props);

        MockHttpServletRequest missingReq = new MockHttpServletRequest("POST", "/internal/messages/send");
        BizException ex1 = assertThrows(BizException.class,
                () -> interceptor.preHandle(missingReq, new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex1.getCode());

        MockHttpServletRequest wrongReq = new MockHttpServletRequest("POST", "/internal/messages/send");
        wrongReq.addHeader(InternalAuthConstants.HEADER_INTERNAL_TOKEN, "wrong-token");
        BizException ex2 = assertThrows(BizException.class,
                () -> interceptor.preHandle(wrongReq, new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex2.getCode());

        MockHttpServletRequest validReq = new MockHttpServletRequest("POST", "/internal/messages/send");
        validReq.addHeader(InternalAuthConstants.HEADER_INTERNAL_TOKEN, "super-secret-internal-token-32bytes!");
        assertTrue(interceptor.preHandle(validReq, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void fileStorageShouldBlockDangerousExtensionsAndFakeMagicBytes() {
        StorageProperties storageProps = new StorageProperties();
        storageProps.setMode("LOCAL");
        FileStorageService service = new FileStorageService(fileObjectMapper, storageProps);

        MockMultipartFile htmlFile = new MockMultipartFile(
                "file", "evil.html", "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class, () -> service.upload(1L, htmlFile));

        MockMultipartFile svgFile = new MockMultipartFile(
                "file", "xss.svg", "image/svg+xml", "<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class, () -> service.upload(1L, svgFile));

        // 伪装成 .png 但实际是 HTML 内容
        MockMultipartFile fakePng = new MockMultipartFile(
                "file", "fake.png", "image/png", "<html>not a png</html>".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class, () -> service.upload(1L, fakePng));

        // 防目录穿越
        assertThrows(BizException.class, () -> service.resolveLocalFile("../etc", "passwd"));
    }

    @Test
    void authSecurityShouldEnforceCaptchaInvitePasswordAndWsTicket() {
        ServerSecurityProperties props = new ServerSecurityProperties();
        props.getAuth().setInviteCode("PULSE-BETA-2026");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        AuthSecurityService service = new AuthSecurityService(redisTemplate, props, mailSenderProvider);

        // 1. 生成 SVG 图形验证码并校验防重放
        CaptchaVO captcha = service.generateCaptcha();
        assertNotNull(captcha.getCaptchaId());
        assertTrue(captcha.getSvgDataUri().startsWith("data:image/svg+xml;base64,"));

        when(valueOps.getAndDelete("im:auth:captcha:cid-1")).thenReturn("AB34");
        service.verifyCaptcha("cid-1", "ab34");

        // 2. 校验内测邀请码
        assertThrows(BizException.class, () -> service.verifyInviteCodeIfRequired("WRONG"));
        service.verifyInviteCodeIfRequired("PULSE-BETA-2026");

        // 3. 密码强度校验
        assertThrows(BizException.class, () -> service.assertPasswordStrength("12345678"));
        assertThrows(BizException.class, () -> service.assertPasswordStrength("abcdefgh"));
        assertThrows(BizException.class, () -> service.assertPasswordStrength("abc1234"));
        service.assertPasswordStrength("Pulse2026");

        // 4. 签发一次性 WS Ticket
        WsTicketVO ticket = service.issueWsTicket(1001L);
        assertNotNull(ticket.getTicket());
        assertEquals(30L, ticket.getExpireSeconds());
        verify(valueOps).set(eq("im:auth:ws-ticket:" + ticket.getTicket()), eq("1001"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void tokenVersionServiceShouldRejectStaleVersionOrDisabledAccount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        TokenVersionService service = new TokenVersionService(userMapper, redisTemplate);

        // 命中缓存且版本不匹配 -> 401
        when(valueOps.get("im:auth:tv:5001")).thenReturn("3:1");
        BizException stale = assertThrows(BizException.class, () -> service.assertValidToken(5001L, 2));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), stale.getCode());

        // 缓存未命中回源 DB，账号已禁用 -> 401
        when(valueOps.get("im:auth:tv:5002")).thenReturn(null);
        ImUser disabled = new ImUser();
        disabled.setId(5002L);
        disabled.setStatus(0);
        disabled.setTokenVersion(1);
        when(userMapper.selectById(5002L)).thenReturn(disabled);
        BizException banned = assertThrows(BizException.class, () -> service.assertValidToken(5002L, 1));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), banned.getCode());
    }
}
