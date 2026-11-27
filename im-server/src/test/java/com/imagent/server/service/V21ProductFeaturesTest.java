package com.imagent.server.service;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.common.security.SecretCryptoUtil;
import com.imagent.server.config.RateLimitProperties;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.dto.ResetPasswordRequest;
import com.imagent.server.dto.UpdateUserLlmConfigRequest;
import com.imagent.server.dto.UserLlmConfigVO;
import com.imagent.server.entity.ImConversation;
import com.imagent.server.entity.ImConversationMember;
import com.imagent.server.entity.ImFriendship;
import com.imagent.server.entity.ImGroupJoinRequest;
import com.imagent.server.entity.ImUser;
import com.imagent.server.entity.ImUserLlmConfig;
import com.imagent.server.entity.ImUserPresenceRule;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImConversationMemberMapper;
import com.imagent.server.mapper.ImFriendRequestMapper;
import com.imagent.server.mapper.ImFriendshipMapper;
import com.imagent.server.mapper.ImGroupJoinRequestMapper;
import com.imagent.server.mapper.ImUserLlmConfigMapper;
import com.imagent.server.mapper.ImUserMapper;
import com.imagent.server.mapper.ImUserPresenceRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class V21ProductFeaturesTest {

    @Mock private ImUserLlmConfigMapper llmConfigMapper;
    @Mock private ImFriendshipMapper friendshipMapper;
    @Mock private ImFriendRequestMapper friendRequestMapper;
    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImConversationMemberMapper conversationMemberMapper;
    @Mock private ImGroupJoinRequestMapper groupJoinRequestMapper;
    @Mock private ImUserPresenceRuleMapper presenceRuleMapper;
    @Mock private ImUserMapper userMapper;
    @Mock private UserService mockUserService;
    @Mock private FriendService mockFriendService;
    @Mock private PresenceService mockPresenceService;
    @Mock private UnreadService mockUnreadService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private MessagePushHelper messagePushHelper;
    @Mock private JwtTokenUtil jwtTokenUtil;
    @Mock private AuthSecurityService authSecurityService;
    @Mock private TokenVersionService tokenVersionService;

    @Test
    void secretCryptoShouldEncryptDecryptWithRandomIvAndMaskKey() {
        String master = "pulseim-test-master-secret-key-32bytes!";
        String raw = "sk-1234567890abcdef9876";
        String enc1 = SecretCryptoUtil.encrypt(raw, master);
        String enc2 = SecretCryptoUtil.encrypt(raw, master);

        assertNotEquals(raw, enc1);
        assertNotEquals(enc1, enc2);
        assertEquals(raw, SecretCryptoUtil.decrypt(enc1, master));
        assertEquals(raw, SecretCryptoUtil.decrypt(enc2, master));
        assertEquals("sk-1****9876", SecretCryptoUtil.maskApiKey(raw));
    }

    @Test
    void userLlmConfigShouldBlockSsrfAndEncryptApiKey() {
        ServerSecurityProperties secProps = new ServerSecurityProperties();
        UserLlmConfigService service = new UserLlmConfigService(llmConfigMapper, secProps);

        // 拦截云元数据与本机 SSRF 地址
        UpdateUserLlmConfigRequest ssrfReq = new UpdateUserLlmConfigRequest();
        ssrfReq.setEnabled(true);
        ssrfReq.setBaseUrl("http://169.254.169.254/latest/meta-data");
        ssrfReq.setApiKey("sk-test-1234567890");
        assertThrows(BizException.class, () -> service.updateConfig(101L, ssrfReq));

        // 合法配置加密落盘并支持解密解析
        when(llmConfigMapper.selectById(101L)).thenReturn(null);
        ArgumentCaptor<ImUserLlmConfig> captor = ArgumentCaptor.forClass(ImUserLlmConfig.class);

        UpdateUserLlmConfigRequest validReq = new UpdateUserLlmConfigRequest();
        validReq.setEnabled(true);
        validReq.setBaseUrl("https://api.deepseek.com/v1");
        validReq.setModel("deepseek-chat");
        validReq.setApiKey("sk-live-secret-9999");

        service.updateConfig(101L, validReq);
        verify(llmConfigMapper).insert(captor.capture());

        ImUserLlmConfig stored = captor.getValue();
        assertEquals(1, stored.getEnabled());
        assertEquals("sk-l****9999", stored.getApiKeyMask());
        assertNotEquals("sk-live-secret-9999", stored.getApiKeyEncrypted());

        when(llmConfigMapper.selectById(101L)).thenReturn(stored);
        UserLlmConfigVO vo = service.getConfig(101L);
        assertTrue(vo.isEnabled());
        assertTrue(vo.isHasApiKey());
        assertEquals("sk-l****9999", vo.getApiKeyMask());

        UserLlmConfigService.ResolvedLlmConfig resolved = service.resolveActiveConfig(101L);
        assertNotNull(resolved);
        assertEquals("sk-live-secret-9999", resolved.apiKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitShouldTripGlobalAiBudgetBreakerButAllowByokUsers() {
        RateLimitProperties props = new RateLimitProperties();
        props.setAgentDailyQuota(100);
        props.setAgentGlobalDailyQuota(500);

        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5L, 501L);

        RateLimitService service = new RateLimitService(redisTemplate, props);

        // 公共用户触发全站 501 > 500 成本熔断
        BizException ex = assertThrows(BizException.class, () -> service.checkAgentTriggerAllowed(2001L, false));
        assertEquals(ErrorCode.AGENT_QUOTA_EXCEEDED.getCode(), ex.getCode());

        // 启用 BYOK 自带 API Key 的用户不受全站公共熔断限制
        service.checkAgentTriggerAllowed(2001L, true);
    }

    @Test
    void friendServiceShouldBlockDirectChatWhenBlockedOrNotFriend() {
        FriendService service = new FriendService(
                friendshipMapper, friendRequestMapper, userMapper, redisTemplate, messagePushHelper, mockPresenceService);

        // 1. 非好友禁止私聊
        when(friendshipMapper.selectOne(any())).thenReturn(null, null);
        BizException nonFriend = assertThrows(BizException.class, () -> service.assertCanDirectChat(1L, 2L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), nonFriend.getCode());

        // 2. 被对方拉黑返回拒收提示
        ImFriendship myFriendEdge = new ImFriendship();
        myFriendEdge.setStatus(FriendService.STATUS_FRIEND);
        ImFriendship peerBlockedEdge = new ImFriendship();
        peerBlockedEdge.setStatus(FriendService.STATUS_BLOCKED);
        when(friendshipMapper.selectOne(any())).thenReturn(myFriendEdge, peerBlockedEdge);
        BizException blocked = assertThrows(BizException.class, () -> service.assertCanDirectChat(1L, 2L));
        assertTrue(blocked.getMessage().contains("拒收"));

        // 3. 互为好友放行
        ImFriendship mutual1 = new ImFriendship();
        mutual1.setStatus(FriendService.STATUS_FRIEND);
        ImFriendship mutual2 = new ImFriendship();
        mutual2.setStatus(FriendService.STATUS_FRIEND);
        when(friendshipMapper.selectOne(any())).thenReturn(mutual1, mutual2);
        service.assertCanDirectChat(1L, 2L);
    }

    @Test
    void forgotPasswordResetShouldBumpTokenVersionAndKickOldSessions() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        ServerSecurityProperties secProps = new ServerSecurityProperties();
        UserService userService = new UserService(
                userMapper, encoder, jwtTokenUtil, redisTemplate,
                authSecurityService, tokenVersionService, messagePushHelper, secProps
        );

        ImUser user = new ImUser();
        user.setId(4001L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setStatus(1);
        user.setUserType(1);
        user.setTokenVersion(3);

        when(authSecurityService.normalizeEmail("alice@example.com")).thenReturn("alice@example.com");
        when(userMapper.selectOne(any())).thenReturn(user);

        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setEmail("alice@example.com");
        req.setEmailCode("888999");
        req.setNewPassword("NewPulse2026");

        userService.resetPasswordByEmail(req, "127.0.0.1");

        verify(authSecurityService).verifyPasswordResetCode("alice@example.com", "888999");
        verify(tokenVersionService).cacheState(4001L, 4, 1);
        verify(authSecurityService).clearLoginFailures("alice@example.com");
        verify(authSecurityService).clearLoginFailures("alice");

        ArgumentCaptor<WsPacket<?>> packetCaptor = ArgumentCaptor.forClass(WsPacket.class);
        verify(messagePushHelper).pushSync(eq(List.of(4001L)), packetCaptor.capture());
        assertEquals(WsPacketType.FORCE_LOGOUT, packetCaptor.getValue().getType());
        verify(jwtTokenUtil, never()).generateToken(any(), any(), eq(3));
    }

    @Test
    void groupJoinApprovalShouldQueuePendingRequestForMemberAndEnforceAdminForAnnouncement() {
        ConversationService service = new ConversationService(
                conversationMapper, conversationMemberMapper, groupJoinRequestMapper,
                userMapper, mockUserService, mockFriendService, mockPresenceService, mockUnreadService,
                messagePushHelper, redisTemplate
        );

        ImConversation group = new ImConversation();
        group.setId(5001L);
        group.setType(2);
        group.setTitle("My family");
        group.setJoinApprovalRequired(1);
        group.setOwnerId(1001L);

        ImConversationMember normalMember = new ImConversationMember();
        normalMember.setConversationId(5001L);
        normalMember.setUserId(1002L);
        normalMember.setRole(1);

        ImConversationMember ownerMember = new ImConversationMember();
        ownerMember.setConversationId(5001L);
        ownerMember.setUserId(1001L);
        ownerMember.setRole(3);

        ImUser invitee = new ImUser();
        invitee.setId(1003L);
        invitee.setNickname("Charlie");

        when(conversationMapper.selectById(5001L)).thenReturn(group);
        when(conversationMemberMapper.selectOne(any())).thenReturn(normalMember);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(java.util.Set.of("1001", "1002"));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(invitee));
        when(mockUserService.getNicknameCached(1002L)).thenReturn("Bob");
        when(groupJoinRequestMapper.selectList(any())).thenReturn(List.of());
        when(conversationMemberMapper.selectList(any())).thenReturn(List.of(ownerMember));

        // 1. 开启进群确认后，普通成员拉人不会直接入群，而是生成待审核申请
        ConversationService.InviteGroupOutcome outcome =
                service.inviteGroupMembers(1002L, 5001L, List.of(1003L));
        assertEquals(0, outcome.result().getJoinedCount());
        assertEquals(1, outcome.result().getPendingApprovalCount());
        verify(groupJoinRequestMapper).insert(any(ImGroupJoinRequest.class));
        verify(conversationMemberMapper, never()).insert(any(ImConversationMember.class));

        // 2. 普通成员无权修改群公告或群验证开关
        BizException annEx = assertThrows(BizException.class,
                () -> service.updateGroupAnnouncement(1002L, 5001L, "新公告"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), annEx.getCode());
    }

    @Test
    void markUnreadAndDeleteChatShouldUpdateMemberStateWithoutDeletingMembership() {
        ConversationService service = new ConversationService(
                conversationMapper, conversationMemberMapper, groupJoinRequestMapper,
                userMapper, mockUserService, mockFriendService, mockPresenceService, mockUnreadService,
                messagePushHelper, redisTemplate
        );

        ImConversation conv = new ImConversation();
        conv.setId(6001L);
        conv.setType(1);
        conv.setCurrentSeq(42L);

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(java.util.Set.of("1001", "1002"));
        when(conversationMapper.selectById(6001L)).thenReturn(conv);

        // 1. 手动标为未读
        service.markAsUnread(1001L, 6001L);
        verify(conversationMemberMapper).markManualUnread(6001L, 1001L);
        verify(mockUnreadService).markManualUnreadCache(1001L, 6001L);

        // 2. 删除该聊天：仅隐藏并记录 cleared_before_seq=42，不删除成员行
        service.deleteChatForMe(1001L, 6001L);
        verify(conversationMemberMapper).hideAndClearForMember(6001L, 1001L, 42L);
        verify(mockUnreadService).clearUserConvUnread(1001L, 6001L);
        verify(conversationMemberMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void presenceServiceShouldRespectAllNoneExceptOnlyModesAndSuppressStealthRead() {
        PresenceService presenceService = new PresenceService(userMapper, presenceRuleMapper, redisTemplate);

        ImUser alice = new ImUser();
        alice.setId(7001L);
        alice.setPresenceMode(PresenceService.MODE_NONE);

        ImUser bob = new ImUser();
        bob.setId(7002L);
        bob.setPresenceMode(PresenceService.MODE_EXCEPT);

        ImUser charlie = new ImUser();
        charlie.setId(7003L);
        charlie.setPresenceMode(PresenceService.MODE_ONLY);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyList())).thenReturn(List.of("gw-1", "gw-1", "gw-1"));
        when(userMapper.selectBatchIds(anyList())).thenReturn(List.of(alice, bob, charlie));

        ImUserPresenceRule bobHideViewer = new ImUserPresenceRule();
        bobHideViewer.setUserId(7002L);
        bobHideViewer.setRuleType(PresenceService.MODE_EXCEPT);
        bobHideViewer.setTargetUserId(9001L);

        ImUserPresenceRule charlieAllowViewer = new ImUserPresenceRule();
        charlieAllowViewer.setUserId(7003L);
        charlieAllowViewer.setRuleType(PresenceService.MODE_ONLY);
        charlieAllowViewer.setTargetUserId(9001L);

        when(presenceRuleMapper.selectList(any())).thenReturn(
                List.of(bobHideViewer),
                List.of(charlieAllowViewer)
        );

        java.util.Map<Long, Boolean> visible = presenceService.resolveVisibleOnlineMap(9001L, List.of(7001L, 7002L, 7003L));
        // alice=NONE -> 隐身离线；bob=EXCEPT(含9001) -> 隐身离线；charlie=ONLY(含9001) -> 可见在线
        assertEquals(false, visible.get(7001L));
        assertEquals(false, visible.get(7002L));
        assertEquals(true, visible.get(7003L));

        // 测试策略对象的群聊隐身判定（忽略 AI 机器人 10000L）
        PresenceService.UserPresencePolicy onlyPolicy = new PresenceService.UserPresencePolicy(
                PresenceService.MODE_ONLY, java.util.Set.of(), java.util.Set.of(9001L));
        assertEquals(false, onlyPolicy.isStealthInMembers(7003L, List.of(7003L, 9001L, 10000L)));
        assertEquals(true, onlyPolicy.isStealthInMembers(7003L, List.of(7003L, 9001L, 9002L)));
    }
}
