package com.imagent.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.common.protocol.payload.ChatSendPayload;
import com.imagent.common.protocol.payload.ServerAckPayload;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceIdempotencyTest {

    @Mock private ImMessageMapper messageMapper;
    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImUserMapper userMapper;
    @Mock private UserService userService;
    @Mock private ConversationService conversationService;
    @Mock private SeqGeneratorService seqGeneratorService;
    @Mock private UnreadService unreadService;
    @Mock private AgentBridgeService agentBridgeService;
    @Mock private RateLimitService rateLimitService;
    @Mock private MessageCollabService messageCollabService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageMapper, conversationMapper, userMapper, userService, conversationService,
                seqGeneratorService, unreadService, agentBridgeService, rateLimitService, messageCollabService,
                redisTemplate, rabbitTemplate, transactionTemplate, objectMapper, Runnable::run
        );
    }

    @Test
    void shouldReturnCachedAckWhenRedisIdempotencyKeyHits() throws Exception {
        ServerAckPayload cached = ServerAckPayload.builder()
                .clientMsgId("c-dup-1").msgId(9001L).conversationId(1001L).seq(7L).build();
        when(conversationService.listMemberIds(1001L)).thenReturn(List.of(2001L, 2002L));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("im:idemp:2001:c-dup-1")).thenReturn(objectMapper.writeValueAsString(cached));

        ChatSendPayload req = ChatSendPayload.builder()
                .conversationId(1001L).clientMsgId("c-dup-1").content("hello").build();

        ServerAckPayload ack = messageService.processIncomingMessage(2001L, req);

        assertEquals(7L, ack.getSeq());
        assertEquals(9001L, ack.getMsgId());
        verify(transactionTemplate, never()).execute(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void shouldFallbackToDbQueryWhenDuplicateKeyOccursConcurrently() {
        when(conversationService.listMemberIds(1001L)).thenReturn(List.of(2001L, 2002L));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("im:idemp:2001:c-race-1")).thenReturn(null);
        when(transactionTemplate.execute(any())).thenThrow(new DuplicateKeyException("uk_sender_client_msg"));

        ImMessage existing = new ImMessage();
        existing.setId(9002L);
        existing.setConversationId(1001L);
        existing.setClientMsgId("c-race-1");
        existing.setSeq(8L);
        when(messageMapper.selectOne(any())).thenReturn(existing);

        ChatSendPayload req = ChatSendPayload.builder()
                .conversationId(1001L).clientMsgId("c-race-1").content("race").build();

        ServerAckPayload ack = messageService.processIncomingMessage(2001L, req);

        assertEquals(8L, ack.getSeq());
        assertEquals(9002L, ack.getMsgId());
        verify(valueOperations).set(eq("im:idemp:2001:c-race-1"), anyString(), any(Duration.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
