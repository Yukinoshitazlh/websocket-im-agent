package com.imagent.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置对称加解密工具（AES-256-GCM，随机 12 字节 IV + 128-bit 认证标签），
 * 用于加密存储用户自带的 LLM API Key（BYOK），避免数据库落盘明文密钥。
 */
public final class SecretCryptoUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretCryptoUtil() {
    }

    public static String encrypt(String plaintext, String masterSecret) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            SecretKeySpec key = deriveAes256Key(masterSecret);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("敏感密钥加密失败", e);
        }
    }

    public static String decrypt(String encoded, String masterSecret) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded.trim());
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文格式不合法");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            SecretKeySpec key = deriveAes256Key(masterSecret);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感密钥解密失败", e);
        }
    }

    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static SecretKeySpec deriveAes256Key(String masterSecret) throws Exception {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalArgumentException("加密主密钥不能为空");
        }
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(masterSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }
}
