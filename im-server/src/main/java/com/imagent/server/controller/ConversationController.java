package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.ConversationMemberVO;
import com.imagent.server.dto.ConversationVO;
import com.imagent.server.dto.CreateDirectConvRequest;
import com.imagent.server.dto.CreateGroupConvRequest;
import com.imagent.server.dto.InviteMembersRequest;
import com.imagent.server.dto.PinnedMessageVO;
import com.imagent.server.dto.RenameGroupRequest;
import com.imagent.server.dto.UpdateConvPrefsRequest;
import com.imagent.server.service.ConversationService;
import com.imagent.server.service.MessageCollabService;
import com.imagent.server.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageCollabService messageCollabService;
    private final MessageService messageService;

    @PostMapping("/direct")
    public Result<ConversationVO> createDirect(@Valid @RequestBody CreateDirectConvRequest request) {
        return Result.ok(conversationService.createOrGetDirectConversation(
                UserContext.getUserId(), request.getTargetUserId()));
    }

    @PostMapping("/group")
    public Result<ConversationVO> createGroup(@Valid @RequestBody CreateGroupConvRequest request) {
        return Result.ok(conversationService.createGroupConversation(
                UserContext.getUserId(), request));
    }

    @GetMapping
    public Result<List<ConversationVO>> listMine() {
        return Result.ok(conversationService.listMyConversations(UserContext.getUserId()));
    }

    /**
     * 会话成员列表（含在线绿点状态与实时已读水位，支撑飞书已读进度圈、@联想选人、群管理）
     */
    @GetMapping("/{convId}/members")
    public Result<List<ConversationMemberVO>> listMembers(@PathVariable Long convId) {
        return Result.ok(conversationService.listConversationMembers(UserContext.getUserId(), convId));
    }

    /**
     * 更新个人会话偏好：置顶 / 免打扰
     */
    @PostMapping("/{convId}/preferences")
    public Result<Void> updatePreferences(@PathVariable Long convId,
                                          @RequestBody UpdateConvPrefsRequest request) {
        conversationService.updatePreferences(UserContext.getUserId(), convId, request.getPinned(), request.getMuted());
        return Result.ok();
    }

    /**
     * 群聊邀请新成员 + 发布系统灰条通知
     */
    @PostMapping("/{convId}/members")
    public Result<Void> inviteMembers(@PathVariable Long convId,
                                      @Valid @RequestBody InviteMembersRequest request) {
        Long userId = UserContext.getUserId();
        String notice = conversationService.inviteGroupMembers(userId, convId, request.getMemberIds());
        messageService.publishSystemMessage(convId, userId, notice);
        return Result.ok();
    }

    /**
     * 群主踢出成员 + 发布系统灰条通知
     */
    @DeleteMapping("/{convId}/members/{targetUserId}")
    public Result<Void> kickMember(@PathVariable Long convId, @PathVariable Long targetUserId) {
        Long userId = UserContext.getUserId();
        String notice = conversationService.removeGroupMember(userId, convId, targetUserId);
        messageService.publishSystemMessage(convId, userId, notice);
        return Result.ok();
    }

    /**
     * 主动退出群聊 + 发布系统灰条通知
     */
    @PostMapping("/{convId}/leave")
    public Result<Void> leaveGroup(@PathVariable Long convId) {
        Long userId = UserContext.getUserId();
        String notice = conversationService.removeGroupMember(userId, convId, userId);
        messageService.publishSystemMessage(convId, userId, notice);
        return Result.ok();
    }

    /**
     * 修改群名称 + 发布系统灰条通知
     */
    @PutMapping("/{convId}/title")
    public Result<Void> renameGroup(@PathVariable Long convId,
                                    @Valid @RequestBody RenameGroupRequest request) {
        Long userId = UserContext.getUserId();
        String notice = conversationService.renameGroup(userId, convId, request.getTitle());
        messageService.publishSystemMessage(convId, userId, notice);
        return Result.ok();
    }

    /**
     * 会话置顶消息列表
     */
    @GetMapping("/{convId}/pins")
    public Result<List<PinnedMessageVO>> listPins(@PathVariable Long convId) {
        return Result.ok(messageCollabService.listPins(UserContext.getUserId(), convId));
    }

    /**
     * 置顶某条消息
     */
    @PostMapping("/{convId}/pins/{msgId}")
    public Result<PinnedMessageVO> pinMessage(@PathVariable Long convId, @PathVariable Long msgId) {
        return Result.ok(messageCollabService.pinMessage(UserContext.getUserId(), convId, msgId));
    }

    /**
     * 取消置顶某条消息
     */
    @DeleteMapping("/{convId}/pins/{msgId}")
    public Result<Void> unpinMessage(@PathVariable Long convId, @PathVariable Long msgId) {
        messageCollabService.unpinMessage(UserContext.getUserId(), convId, msgId);
        return Result.ok();
    }
}
