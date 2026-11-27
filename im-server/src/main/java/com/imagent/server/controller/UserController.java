package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.PresenceSettingsVO;
import com.imagent.server.dto.SendBindEmailCodeRequest;
import com.imagent.server.dto.SendEmailCodeVO;
import com.imagent.server.dto.UpdateEmailRequest;
import com.imagent.server.dto.UpdatePresenceSettingsRequest;
import com.imagent.server.dto.UpdateProfileRequest;
import com.imagent.server.dto.UpdateUserLlmConfigRequest;
import com.imagent.server.dto.UserLlmConfigVO;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.service.FriendService;
import com.imagent.server.service.PresenceService;
import com.imagent.server.service.UserLlmConfigService;
import com.imagent.server.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FriendService friendService;
    private final PresenceService presenceService;
    private final UserLlmConfigService userLlmConfigService;

    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        return Result.ok(userService.getProfile(UserContext.getUserId()));
    }

    @PutMapping("/me")
    public Result<UserProfileVO> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(UserContext.getUserId(), request));
    }

    @PostMapping("/me/email/code")
    public Result<SendEmailCodeVO> sendBindEmailCode(@Valid @RequestBody SendBindEmailCodeRequest request,
                                                     HttpServletRequest httpRequest) {
        return Result.ok(userService.sendBindEmailCode(
                UserContext.getUserId(),
                request.getEmail(),
                extractClientIp(httpRequest)
        ));
    }

    @PutMapping("/me/email")
    public Result<UserProfileVO> updateEmail(@Valid @RequestBody UpdateEmailRequest request) {
        return Result.ok(userService.updateEmail(UserContext.getUserId(), request));
    }

    @PutMapping("/me/password")
    public Result<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return Result.ok(userService.changePassword(UserContext.getUserId(), request));
    }

    @GetMapping("/me/llm-config")
    public Result<UserLlmConfigVO> getMyLlmConfig() {
        return Result.ok(userLlmConfigService.getConfig(UserContext.getUserId()));
    }

    @PutMapping("/me/llm-config")
    public Result<UserLlmConfigVO> updateMyLlmConfig(@Valid @RequestBody UpdateUserLlmConfigRequest request) {
        return Result.ok(userLlmConfigService.updateConfig(UserContext.getUserId(), request));
    }

    @GetMapping("/me/presence")
    public Result<PresenceSettingsVO> getMyPresence() {
        return Result.ok(presenceService.getPresenceSettings(UserContext.getUserId()));
    }

    @PutMapping("/me/presence")
    public Result<PresenceSettingsVO> updateMyPresence(@Valid @RequestBody UpdatePresenceSettingsRequest request) {
        return Result.ok(presenceService.updatePresenceSettings(UserContext.getUserId(), request));
    }

    /**
     * 查看任意用户公开名片资料（含遵循隐私规则的实时在线状态、个性签名、简介、自定义状态与好友关系状态）
     */
    @GetMapping("/{userId}")
    public Result<UserProfileVO> profileById(@PathVariable Long userId) {
        Long me = UserContext.getUserId();
        UserProfileVO vo = userService.getProfile(userId);
        vo.setOnline(presenceService.isOnlineVisibleTo(userId, me));
        friendService.enrichRelationStatus(me, List.of(vo));
        return Result.ok(vo);
    }

    @GetMapping("/search")
    public Result<List<UserProfileVO>> search(@RequestParam(required = false) String keyword) {
        Long me = UserContext.getUserId();
        List<UserProfileVO> list = userService.searchUsers(keyword, me);
        friendService.enrichRelationStatus(me, list);
        return Result.ok(list);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
