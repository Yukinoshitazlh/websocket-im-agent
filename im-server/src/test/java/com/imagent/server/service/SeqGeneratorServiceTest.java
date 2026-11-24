package com.imagent.server.service;

import com.imagent.server.mapper.ImMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeqGeneratorServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ImMessageMapper messageMapper;

    @InjectMocks
    private SeqGeneratorService seqGeneratorService;

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseFastPathWhenSeqKeyExistsInRedis() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("im:seq:conv:1001")))).thenReturn(5L);

        Long seq = seqGeneratorService.nextSeq(1001L);

        assertEquals(5L, seq);
        verify(messageMapper, never()).selectMaxSeq(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecoverFromDbWithDistributedLockOnColdStart() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("im:seq:conv:1002"))))
                .thenReturn(-1L, -1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("im:lock:seq:init:1002"), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(messageMapper.selectMaxSeq(1002L)).thenReturn(10L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("im:lock:seq:init:1002")), any()))
                .thenReturn(1L);

        Long seq = seqGeneratorService.nextSeq(1002L);

        assertEquals(11L, seq);
        verify(valueOperations).set("im:seq:conv:1002", "11");
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("im:lock:seq:init:1002")), any());
    }
}
