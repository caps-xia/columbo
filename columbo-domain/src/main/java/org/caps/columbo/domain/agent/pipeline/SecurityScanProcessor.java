package org.caps.columbo.domain.agent.pipeline;

import org.caps.columbo.types.utils.sensitive.WordFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全扫描处理器（对话消息进入智能体前的确定性安检）。
 * 规则改编自 waliapi-java 安全扫描器（CredentialScanner/TrackingScanner/UnicodeScanner），
 * 按客服排查场景裁剪为四层处置：
 * <ul>
 *   <li>剥离（改写消息）：零宽/不可见字符、ANSI 转义——无正常用途，典型注入载体，直接清除；</li>
 *   <li>掩码（改写消息）：敏感词（DFA，支持全角/大小写/停顿词变形）——仅当配置了词库才启用，
 *       ai.agent.security.sensitive-words 逗号分隔，默认空即直通；</li>
 *   <li>审计（不改写）：注入话术——命中记 WARN 并打 attributes 标记，提示词已含忽略规则，误杀代价高于漏报；</li>
 *   <li>审计（不改写）：凭证形态——用户主动提供 token（如【线上token:xxx】）属正常业务，只掩码记日志留痕。</li>
 * </ul>
 * 永不拦截消息（返回恒 true）：客服场景误杀代价高，本处理器只做"确定性降噪 + 留痕"。
 */
@Slf4j
@Component("securityScanProcessor")
public class SecurityScanProcessor implements PipelineProcessor<ChatPipelineContext> {

    /** 敏感词词库：逗号分隔；空（默认）= 敏感词掩码层直通 */
    @Value("${ai.agent.security.sensitive-words:}")
    private String sensitiveWordsConfig;

    /** 敏感词过滤器（词库为空时 isEmpty()=true，doFilter 直通） */
    private WordFilter sensitiveFilter = new WordFilter();

    @PostConstruct
    void initSensitiveFilter() {
        List<String> words = Arrays.stream(sensitiveWordsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (!words.isEmpty()) {
            sensitiveFilter = WordFilter.of(words);
            log.info("安全扫描：敏感词掩码已启用 words={}（支持全角/大小写/停顿词变形匹配）", words.size());
        }
    }

    /** 零宽/不可见字符：零宽空格、方向控制、变体选择符、软连字符、BOM——无正常用途 */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\u2061-\\u2064\\uFEFF\\u00AD]");

    /** ANSI 转义序列：终端复制粘贴的污染源 */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");

    /** 注入话术（命中只审计不改写；中英都覆盖——两次线上污染分别是英文回声与外语注入） */
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|prompts?|content)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("kindly\\s+disregard", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do\\s+not\\s+comply", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+a", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s+mode|jailbreak", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略(之前|以上|上述|前面)的?(所有)?(指令|提示|设定|规则)"),
            Pattern.compile("无视(之前|以上|上述|前面)的?(所有)?(指令|提示|设定|规则)"),
            Pattern.compile("你现在是(一?个)"),
            Pattern.compile("不要遵守|不要服从"),
    };

    /** 凭证形态（命中只掩码记日志：密钥/令牌出现在消息里必须留痕） */
    private static final Pattern[] CREDENTIAL_PATTERNS = {
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]+"),
            Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            Pattern.compile("-----BEGIN[\\w\\s]*PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?token)\\s*[=:]\\s*\\S{6,}"),
    };

    @Override
    public boolean doProcess(ChatPipelineContext context) {
        String message = context.getMessage();
        if (message == null || message.isEmpty()) {
            return true;
        }

        // 1) 剥离不可见字符与 ANSI 转义（确定性改写，安全）
        String cleaned = ANSI_ESCAPE.matcher(INVISIBLE_CHARS.matcher(message).replaceAll("")).replaceAll("");
        if (!cleaned.equals(message)) {
            log.info("安全扫描：剥离不可见字符/ANSI 转义 userId={} 原长={} 新长={}",
                    context.getUserId(), message.length(), cleaned.length());
            message = cleaned;
            context.setMessage(message);
        }

        // 1.5) 敏感词掩码（确定性改写；词库为空时直通，行为与未引入本层时完全一致）
        if (!sensitiveFilter.isEmpty()) {
            String masked = sensitiveFilter.doFilter(message);
            if (!masked.equals(message)) {
                log.info("安全扫描：敏感词掩码 userId={} 原长={} 新长={}",
                        context.getUserId(), message.length(), masked.length());
                context.getAttributes().put("security.sensitiveMasked", true);
                message = masked;
                context.setMessage(message);
            }
        }

        // 2) 注入话术审计（不改写：attributes 标记供后续节点/排障取用）
        List<String> injectionHits = matchAll(message, INJECTION_PATTERNS);
        if (!injectionHits.isEmpty()) {
            log.warn("安全扫描：检出疑似提示词注入 userId={} hits={} 首个={}",
                    context.getUserId(), injectionHits.size(), mask(injectionHits.get(0)));
            context.getAttributes().put("security.injectionHits", injectionHits.size());
        }

        // 3) 凭证形态审计（掩码留痕，不改写——用户主动提供 token 是正常业务）
        List<String> credentialHits = matchAll(message, CREDENTIAL_PATTERNS);
        if (!credentialHits.isEmpty()) {
            List<String> masked = credentialHits.stream().map(SecurityScanProcessor::mask).toList();
            log.warn("安全扫描：消息含疑似凭证（已掩码留痕） userId={} hits={}", context.getUserId(), masked);
            context.getAttributes().put("security.credentialHits", credentialHits.size());
        }

        return true;
    }

    private static List<String> matchAll(String message, Pattern[] patterns) {
        List<String> hits = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                hits.add(matcher.group());
                if (hits.size() >= 10) {
                    return hits;
                }
            }
        }
        return hits;
    }

    /** 掩码：保留前 4 字符 + 长度提示，防止凭证原文进日志 */
    static String mask(String value) {
        int keep = Math.min(4, value.length());
        return value.substring(0, keep) + "***<len=" + value.length() + ">";
    }
}
