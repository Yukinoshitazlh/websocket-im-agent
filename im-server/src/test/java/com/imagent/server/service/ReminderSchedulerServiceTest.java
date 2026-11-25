package com.imagent.server.service;

import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.mapper.ImGroupReminderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReminderSchedulerServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ImGroupReminderMapper reminderMapper;
    @Mock
    private MessageService messageService;

    private ReminderSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        schedulerService = new ReminderSchedulerService(redisTemplate, reminderMapper, messageService);
    }

    @Test
    void shouldPublishReminderWhenCasClaimSucceeds() {
        ImGroupReminder reminder = new ImGroupReminder();
        reminder.setId(9001L);
        reminder.setConversationId(2001L);
        reminder.setTitle("代码评审提醒");
        reminder.setContent("15秒后开始代码评审");

        when(reminderMapper.markSentIfPending(9001L)).thenReturn(1);
        when(reminderMapper.selectById(9001L)).thenReturn(reminder);

        schedulerService.triggerReminderIfPending(9001L);

        verify(messageService, times(1)).publishScheduledReminder(reminder);
    }

    @Test
    void shouldSkipDuplicateTriggerWhenCasClaimReturnsZero() {
        when(reminderMapper.markSentIfPending(9002L)).thenReturn(0);

        schedulerService.triggerReminderIfPending(9002L);

        verify(reminderMapper, never()).selectById(anyLong());
        verify(messageService, never()).publishScheduledReminder(any());
    }
}
