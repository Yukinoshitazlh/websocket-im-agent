package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.ConversationVO;
import com.imagent.server.dto.FriendRequestVO;
import com.imagent.server.dto.SendFriendRequestDTO;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.service.ConversationService;
import com.imagent.server.service.FriendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;
    private final ConversationService conversationService;

    @GetMapping
    public Result<List<UserProfileVO>> listFriends(@RequestParam(required = false) String keyword) {
        return Result.ok(friendService.listFriends(UserContext.getUserId(), keyword));
    }

    @GetMapping("/blocked")
    public Result<List<UserProfileVO>> listBlocked() {
        return Result.ok(friendService.listBlockedUsers(UserContext.getUserId()));
    }

    @GetMapping("/requests")
    public Result<List<FriendRequestVO>> listRequests() {
        return Result.ok(friendService.listFriendRequests(UserContext.getUserId()));
    }

    @PostMapping("/requests")
    public Result<FriendRequestVO> sendRequest(@Valid @RequestBody SendFriendRequestDTO req) {
        return Result.ok(friendService.sendFriendRequest(
                UserContext.getUserId(), req.getTargetUserId(), req.getRemark()));
    }

    @PostMapping("/requests/{requestId}/accept")
    public Result<ConversationVO> acceptRequest(@PathVariable Long requestId) {
        Long userId = UserContext.getUserId();
        Long peerId = friendService.acceptFriendRequest(userId, requestId);
        // 同意好友后自动就绪双方单聊会话，点击即可开聊
        return Result.ok(conversationService.createOrGetDirectConversation(userId, peerId));
    }

    @PostMapping("/requests/{requestId}/reject")
    public Result<Void> rejectRequest(@PathVariable Long requestId) {
        friendService.rejectFriendRequest(UserContext.getUserId(), requestId);
        return Result.ok();
    }

    @DeleteMapping("/{friendId}")
    public Result<Void> deleteFriend(@PathVariable Long friendId) {
        friendService.deleteFriend(UserContext.getUserId(), friendId);
        return Result.ok();
    }

    @PostMapping("/{targetUserId}/block")
    public Result<Void> blockUser(@PathVariable Long targetUserId) {
        friendService.blockUser(UserContext.getUserId(), targetUserId);
        return Result.ok();
    }

    @DeleteMapping("/{targetUserId}/block")
    public Result<Void> unblockUser(@PathVariable Long targetUserId) {
        friendService.unblockUser(UserContext.getUserId(), targetUserId);
        return Result.ok();
    }
}
