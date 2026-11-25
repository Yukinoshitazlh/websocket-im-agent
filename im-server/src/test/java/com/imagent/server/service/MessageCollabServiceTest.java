package com.imagent.server.service;

import com.imagent.server.dto.PinnedMessageVO;
import com.imagent.server.entity.ImConversationPin;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.entity.ImMessageReaction;
import com.imagent.server.mapper.ImConversationPinMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImMessageReactionMapper;
import com.imagent.server.mapper.ImUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCollabServiceTest {

    @Mock private ImMessageReactionMapper reactionMapper;
    @Mock private ImConversationPinMapper pinMapper;
    @Mock private ImMessageMapper messageMapper;
    @Mock private ImUserMapper userMapper;
    @Mock private UserService userService;
    @Mock private ConversationService conversationService;
    @Mock private MessagePushHelper pushHelper;

    private MessageCollabService collabService;

    @BeforeEach
    void setUp() {
        collabService = new MessageCollabService(
                reactionMapper, pinMapper, messageMapper, userMapper, userService, conversationService, pushHelper
        );
    }

    @Test
    void shouldAddAndBroadcastReactionWhenNotYetReacted() {
        ImMessage msg = new ImMessage();
        msg.setId(5001L);
        msg.setConversationId(1001L);
        msg.setSenderId(2001L);
        msg.setStatus(1);

        when(messageMapper.selectById(5001L)).thenReturn(msg);
        when(conversationService.listMemberIds(1001L)).thenReturn(List.of(2001L, 2002L));
        when(reactionMapper.selectOne(any())).thenReturn(null);
        when(userService.getNicknameCached(2002L)).thenReturn("Bob");
        when(reactionMapper.selectList(any())).thenReturn(Collections.emptyList());

        collabService.toggleReaction(2002L, 5001L, "👍");

        verify(reactionMapper).insert(any(ImMessageReaction.class));
        verify(pushHelper).pushAsync(eq(List.of(2001L, 2002L)), any());
    }

    @Test
    void shouldRemoveReactionWhenAlreadyReacted() {
        ImMessage msg = new ImMessage();
        msg.setId(5002L);
        msg.setConversationId(1001L);
        msg.setSenderId(2001L);
        msg.setStatus(1);

        ImMessageReaction existing = new ImMessageReaction();
        existing.setId(88L);
        existing.setMsgId(5002L);
        existing.setUserId(2002L);
        existing.setEmoji("🔥");

        when(messageMapper.selectById(5002L)).thenReturn(msg);
        when(conversationService.listMemberIds(1001L)).thenReturn(List.of(2001L, 2002L));
        when(reactionMapper.selectOne(any())).thenReturn(existing);
        when(userService.getNicknameCached(2002L)).thenReturn("Bob");
        when(reactionMapper.selectList(any())).thenReturn(Collections.emptyList());

        collabService.toggleReaction(2002L, 5002L, "🔥");

        verify(reactionMapper).deleteById(88L);
        verify(pushHelper).pushAsync(eq(List.of(2001L, 2002L)), any());
    }

    @Test
    void shouldPinMessageAndBroadcastPinEvent() {
        ImMessage msg = new ImMessage();
        msg.setId(5003L);
        msg.setConversationId(1001L);
        msg.setSenderId(2001L);
        msg.setSeq(12L);
        msg.setMsgType("TEXT");
        msg.setContent("今晚8点发布新版");
        msg.setStatus(1);

        when(conversationService.listMemberIds(1001L)).thenReturn(List.of(2001L, 2002L));
        when(messageMapper.selectById(5003L)).thenReturn(msg);
        when(userService.getNicknameCached(2001L)).thenReturn("Alice");
        when(userService.getNicknameCached(2002L)).thenReturn("Bob");

        PinnedMessageVO pinned = collabService.pinMessage(2002L, 1001L, 5003L);

        assertEquals(5003L, pinned.getMsgId());
        assertEquals(12L, pinned.getSeq());
        assertEquals("今晚8点发布新版", pinned.getPreview());
        verify(pinMapper).insert(any(ImConversationPin.class));
        verify(pushHelper).pushAsync(eq(List.of(2001L, 2002L)), any());
    }
}
