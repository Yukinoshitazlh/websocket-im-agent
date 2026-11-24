package com.imagent.agent.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveTaskRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldDisposeInFlightStreamImmediatelyOnCancel() {
        ActiveTaskRegistry registry = new ActiveTaskRegistry(redisTemplate);
        Disposable stream = mock(Disposable.class);
        when(stream.isDisposed()).thenReturn(false);

        registry.register(7001L);
        registry.attachStream(7001L, stream);
        registry.cancel(7001L);

        assertTrue(registry.isCancelled(7001L));
        verify(stream).dispose();
    }
}
