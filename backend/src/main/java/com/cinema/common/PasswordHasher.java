package com.cinema.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码哈希工具（SHA-256 + 每用户随机盐）。
 *
 * <p>选型说明：任务卡依赖清单未包含 Spring Security，为保持依赖最小化采用 JDK 自带
 * SHA-256 + 每用户随机盐。存储格式 {@code salt$sha256hex}（16 位盐字符 + '$' + 64 位摘要，
 * 共 81 字符，落库 password_hash VARCHAR(100)）。若后续需升级 BCrypt/Argon2，
 * 调用方仅依赖 encode/verify 两个方法，替换本类实现即可。
 */
public final class PasswordHasher {

    /** 随机盐字节数（8 字节 = 16 个十六进制字符）。 */
    private static final int SALT_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成密码存储串：salt$sha256hex。 */
    public static String encode(String rawPassword) {
        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return salt + "$" + hash(rawPassword, salt);
    }

    /** 校验明文密码与存储串是否匹配（常量时间比较，防时序侧信道）。 */
    public static boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        int separator = stored.indexOf('$');
        if (separator <= 0) {
            return false;
        }
        String salt = stored.substring(0, separator);
        String expected = stored.substring(separator + 1);
        String actual = hash(rawPassword, salt);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    /** 计算 SHA-256(salt + rawPassword) 的十六进制摘要。 */
    public static String hash(String rawPassword, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + rawPassword).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256 实现", e);
        }
    }
}
