package com.imagent.server.service;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.security.SecretCryptoUtil;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.dto.UpdateUserLlmConfigRequest;
import com.imagent.server.dto.UserLlmConfigVO;
import com.imagent.server.entity.ImUserLlmConfig;
import com.imagent.server.mapper.ImUserLlmConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * 用户自带大模型 API Key（BYOK）管理服务：
 * - AES-256-GCM 加密落盘，响应仅返回脱敏 apiKeyMask；
 * - 严格校验 Base URL 协议与禁止云元数据/本地高危地址防 SSRF。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLlmConfigService {

    public record ResolvedLlmConfig(String baseUrl, String apiKey, String model) {
    }

    private final ImUserLlmConfigMapper configMapper;
    private final ServerSecurityProperties securityProperties;

    public UserLlmConfigVO getConfig(Long userId) {
        ImUserLlmConfig cfg = configMapper.selectById(userId);
        if (cfg == null) {
            return UserLlmConfigVO.builder()
                    .enabled(false)
                    .baseUrl("https://api.deepseek.com/v1")
                    .model("deepseek-chat")
                    .apiKeyMask("")
                    .hasApiKey(false)
                    .build();
        }
        boolean hasKey = cfg.getApiKeyEncrypted() != null && !cfg.getApiKeyEncrypted().isBlank();
        return UserLlmConfigVO.builder()
                .enabled(Integer.valueOf(1).equals(cfg.getEnabled()) && hasKey)
                .baseUrl(Objects.requireNonNullElse(cfg.getBaseUrl(), ""))
                .model(Objects.requireNonNullElse(cfg.getModel(), ""))
                .apiKeyMask(Objects.requireNonNullElse(cfg.getApiKeyMask(), ""))
                .hasApiKey(hasKey)
                .build();
    }

    public UserLlmConfigVO updateConfig(Long userId, UpdateUserLlmConfigRequest req) {
        ImUserLlmConfig existing = configMapper.selectById(userId);
        boolean isNew = existing == null;
        ImUserLlmConfig entity = isNew ? new ImUserLlmConfig() : existing;
        entity.setUserId(userId);

        if (Boolean.TRUE.equals(req.getClearApiKey())) {
            entity.setEnabled(0);
            entity.setApiKeyEncrypted(null);
            entity.setApiKeyMask("");
            if (isNew) {
                entity.setBaseUrl("https://api.deepseek.com/v1");
                entity.setModel("deepseek-chat");
                configMapper.insert(entity);
            } else {
                configMapper.updateById(entity);
            }
            return getConfig(userId);
        }

        String baseUrl = req.getBaseUrl() != null ? req.getBaseUrl().trim() : Objects.requireNonNullElse(entity.getBaseUrl(), "");
        String model = req.getModel() != null ? req.getModel().trim() : Objects.requireNonNullElse(entity.getModel(), "");

        if (!baseUrl.isEmpty()) {
            validateSafeLlmBaseUrl(baseUrl);
        }
        entity.setBaseUrl(baseUrl.isEmpty() ? "https://api.deepseek.com/v1" : baseUrl);
        entity.setModel(model.isEmpty() ? "deepseek-chat" : model);

        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            String rawKey = req.getApiKey().trim();
            entity.setApiKeyEncrypted(SecretCryptoUtil.encrypt(rawKey, securityProperties.getJwtSecret()));
            entity.setApiKeyMask(SecretCryptoUtil.maskApiKey(rawKey));
        }

        boolean hasKey = entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
        boolean wantEnable = Boolean.TRUE.equals(req.getEnabled());
        if (wantEnable && !hasKey) {
            throw new BizException(ErrorCode.BAD_REQUEST, "启用自定义大模型前请先填写 API Key");
        }
        entity.setEnabled(wantEnable ? 1 : 0);

        if (isNew) {
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
        return getConfig(userId);
    }

    public boolean hasActiveByok(Long userId) {
        if (userId == null) {
            return false;
        }
        ImUserLlmConfig cfg = configMapper.selectById(userId);
        return cfg != null
                && Integer.valueOf(1).equals(cfg.getEnabled())
                && cfg.getApiKeyEncrypted() != null
                && !cfg.getApiKeyEncrypted().isBlank();
    }

    public ResolvedLlmConfig resolveActiveConfig(Long userId) {
        if (userId == null) {
            return null;
        }
        ImUserLlmConfig cfg = configMapper.selectById(userId);
        if (cfg == null || !Integer.valueOf(1).equals(cfg.getEnabled())
                || cfg.getApiKeyEncrypted() == null || cfg.getApiKeyEncrypted().isBlank()) {
            return null;
        }
        try {
            String apiKey = SecretCryptoUtil.decrypt(cfg.getApiKeyEncrypted(), securityProperties.getJwtSecret());
            if (apiKey.isBlank()) {
                return null;
            }
            String baseUrl = (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank())
                    ? "https://api.deepseek.com/v1" : cfg.getBaseUrl().trim();
            String model = (cfg.getModel() == null || cfg.getModel().isBlank())
                    ? "deepseek-chat" : cfg.getModel().trim();
            return new ResolvedLlmConfig(baseUrl, apiKey, model);
        } catch (Exception e) {
            log.warn("解密用户 BYOK 配置失败，回退服务端公共模型: userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }

    void validateSafeLlmBaseUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "API Base URL 格式不合法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "API Base URL 仅支持 http/https 协议");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("169.254.169.254") || host.equals("metadata.google.internal")
                || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.startsWith("10.") || host.startsWith("192.168.")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "出于 SSRF 安全防护，不支持填写本机或内网元数据地址");
        }
    }
}
