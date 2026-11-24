package com.imagent.server.service;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setAgentDailyQuota(10);
        rateLimitService = new RateLimitService(redisTemplate, props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectChatSendWhenTokenBucketExhausted() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        BizException ex = assertThrows(BizException.class, () -> rateLimitService.checkChatSendAllowed(2001L));
        assertEquals(ErrorCode.RATE_LIMITED.getCode(), ex.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectAgentTriggerWhenDailyQuotaExceeded() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(11L);

        BizException ex = assertThrows(BizException.class, () -> rateLimitService.checkAgentTriggerAllowed(2001L));
        assertEquals(ErrorCode.AGENT_QUOTA_EXCEEDED.getCode(), ex.getCode());
    }
}
