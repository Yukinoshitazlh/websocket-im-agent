package com.imagent.server.controller;

import com.imagent.common.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Result<Map<String, Object>> ping() {
        return Result.ok(Map.of(
                "service", "im-server",
                "status", "UP",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
