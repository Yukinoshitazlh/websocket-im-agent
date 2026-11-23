package com.imagent.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JwtTokenUtilTest {

    @Test
    void shouldGenerateAndExtractUserId() {
        JwtTokenUtil jwtTokenUtil = new JwtTokenUtil("im-agent-local-dev-jwt-secret-key-32bytes!", 3600);
        String token = jwtTokenUtil.generateToken(2001L, "alice");
        assertNotNull(token);
        assertEquals(2001L, jwtTokenUtil.extractUserId(token));
    }
}
