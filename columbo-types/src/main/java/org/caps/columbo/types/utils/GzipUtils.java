package org.caps.columbo.types.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 字符串 GZIP + Base64 压缩工具：适合长文本入库/传输前压缩（如聊天会话内容、大 JSON 字段）。
 *
 * <p>compress 超过阈值才压缩，低于阈值原样返回；decompress 对未压缩内容原样返回（按 GZIP 魔数识别，
 * 兼容存量未压缩数据），因此压/解两侧可以无脑成对使用。
 *
 * <p>提炼自 YL hospital-admin-api-web 的 JsonCompressionUtil + JsonCompressionUtilProxy（合并为一个类），
 * 去除了 hutool JSON 校验依赖——是否压缩与内容是不是 JSON 无关，用魔数判断更准确。
 */
public final class GzipUtils {

    /** 默认压缩阈值：超过 3 万字符才值得压 */
    public static final int DEFAULT_COMPRESS_THRESHOLD = 30000;

    private static final byte GZIP_MAGIC_1 = 0x1f;
    private static final byte GZIP_MAGIC_2 = (byte) 0x8b;

    private GzipUtils() {
    }

    /**
     * 压缩（超过默认阈值才压，否则原样返回）。
     */
    public static String compress(String text) {
        return compress(text, DEFAULT_COMPRESS_THRESHOLD);
    }

    /**
     * 压缩：长度 >= threshold 才执行 GZIP+Base64，否则原样返回。
     */
    public static String compress(String text, int threshold) {
        if (text == null || text.length() < threshold) {
            return text;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("gzip compress failed", e);
        }
    }

    /**
     * 解压：内容不是 Base64(GZIP) 格式时原样返回（兼容未压缩存量数据）。
     */
    public static String decompress(String input) {
        if (!isCompressed(input)) {
            return input;
        }
        byte[] decoded = Base64.getDecoder().decode(input);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("gzip decompress failed", e);
        }
    }

    /**
     * 是否为 compress 的产物（Base64 可解且为 GZIP 魔数开头）。
     */
    public static boolean isCompressed(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(input);
            return decoded.length >= 2 && decoded[0] == GZIP_MAGIC_1 && decoded[1] == GZIP_MAGIC_2;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
