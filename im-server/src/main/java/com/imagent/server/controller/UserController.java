package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.UserProfileVO;
import com.imagent.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/search")
    public Result<List<UserProfileVO>> search(@RequestParam(required = false) String keyword) {
        return Result.ok(userService.searchUsers(keyword, UserContext.getUserId()));
    }
}
