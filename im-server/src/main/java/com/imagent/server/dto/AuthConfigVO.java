package com.imagent.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthConfigVO {
    private boolean requireCaptcha;
    private boolean requireEmailCode;
    private boolean inviteCodeRequired;
    private boolean allowDemoQuickLogin;
    private boolean showDemoMode;
}
