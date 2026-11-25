package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.ChangePasswordRequest;
import com.imagent.server.dto.UpdateProfileRequest;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        return Result.ok(userService.getProfile(UserContext.getUserId()));
    }

    @PutMapping("/me")
    public Result<UserProfileVO> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(UserContext.getUserId(), request));
    }

    @PutMapping("/me/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(UserContext.getUserId(), request);
        return Result.ok();
    }

    /**
     * 查看任意用户公开名片资料（含实时在线状态、个性签名、简介、自定义状态）
     */
    @GetMapping("/{userId}")
    public Result<UserProfileVO> profileById(@PathVariable Long userId) {
        return Result.ok(userService.getProfile(userId));
    }

    @GetMapping("/search")
    public Result<List<UserProfileVO>> search(@RequestParam(required = false) String keyword) {
        return Result.ok(userService.searchUsers(keyword, UserContext.getUserId()));
    }
}
