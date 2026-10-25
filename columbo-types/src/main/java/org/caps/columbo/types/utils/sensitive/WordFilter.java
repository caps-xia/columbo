package org.caps.columbo.types.utils.sensitive;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DFA 敏感词过滤器：支持全角/半角归一化、大写归一化、停顿词（插入无关字符）绕过检测，
 * 长词优先匹配（配置"日你"与"日你妹"时命中长的）。
 *
 * <p>词库由调用方注入（DFA 首字位图 + 子节点链匹配，O(文本长度)），实例非线程安全但构建后只读使用无问题，
 * 建议作为单例持有。
 *
 * <p>提炼自 YL hospital-tongxing-all tx-utils 的 WordFilter：
 * 去掉了 classpath 词库文件加载（wd.txt/stopwd.txt）与公司异常依赖，词库一律显式传入。
 *
 * <pre>
 *     WordFilter filter = WordFilter.of(List.of("敏感词", "sensitive"));
 *     filter.doFilter("包含敏感词的文本");   // 包含***的文本
 *     filter.isContains("包含敏感词的文本"); // true
 * </pre>
 */
public final class WordFilter {

    /** 首字位图（0~65535 是否为敏感词开头） */
    private final FilterSet set = new FilterSet();

    /** 首字 → DFA 节点 */
    private final Map<Integer, WordNode> nodes = new HashMap<>(1024, 1);

    /** 停顿词集合（匹配时跳过这些字符，防"敏*感*词"绕过） */
    private final Set<Integer> stopWords = new HashSet<>();

    /** 命中敏感词的替换字符 */
    private static final char SIGN = '*';

    /** 空过滤器：所有文本直接放行，之后可继续 addSensitiveWord/addStopWord */
    public WordFilter() {
    }

    public static WordFilter of(List<String> sensitiveWords) {
        WordFilter filter = new WordFilter();
        filter.addSensitiveWord(sensitiveWords);
        return filter;
    }

    public static WordFilter of(List<String> sensitiveWords, List<String> stopWords) {
        WordFilter filter = new WordFilter();
        filter.addSensitiveWord(sensitiveWords);
        filter.addStopWord(stopWords);
        return filter;
    }

    /** 增加停顿词（逐字符加入，如 " *-" 能挡住"敏 感 词"/"敏*感*词"） */
    public void addStopWord(final List<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        for (String curr : words) {
            for (char c : curr.toCharArray()) {
                stopWords.add(charConvert(c));
            }
        }
    }

    /** 增加敏感词 */
    public void addSensitiveWord(final List<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        for (String curr : words) {
            if (curr != null && !curr.trim().isEmpty()) {
                addSensitiveWordHandle(curr.trim());
            }
        }
    }

    private void addSensitiveWordHandle(String curr) {
        char[] chs = curr.toCharArray();
        int firstChar = charConvert(chs[0]);
        WordNode node;
        if (!set.contains(firstChar)) {
            set.add(firstChar);
            node = new WordNode(firstChar, chs.length == 1);
            nodes.put(firstChar, node);
        } else {
            node = nodes.get(firstChar);
            if (!node.isLast() && chs.length == 1) {
                // 该字已作为更长词的前缀存在，补"单独成词"标记
                node.markLast();
            }
        }
        int lastIndex = chs.length - 1;
        for (int i = 1; i < chs.length; i++) {
            node = node.addIfNoExist(charConvert(chs[i]), i == lastIndex);
        }
    }

    /**
     * 过滤：将文本中的敏感词替换为 '*'，长词优先。
     */
    public String doFilter(final String src) {
        if (src == null || src.isEmpty() || nodes.isEmpty()) {
            return src;
        }
        char[] chs = src.toCharArray();
        int length = chs.length;
        int currC;
        int cpCurrC;
        int k;
        WordNode node;
        for (int i = 0; i < length; i++) {
            currC = charConvert(chs[i]);
            if (!set.contains(currC)) {
                continue;
            }
            node = nodes.get(currC);
            if (node == null) {
                continue;
            }
            boolean couldMark = false;
            int markNum = -1;
            if (node.isLast()) {
                // 单字成词
                couldMark = true;
                markNum = 0;
            }
            // 继续向后匹配（长词优先），跳过重复字符与停顿词
            k = i;
            cpCurrC = currC;
            while (++k < length) {
                int temp = charConvert(chs[k]);
                if (temp == cpCurrC) {
                    continue;
                }
                if (stopWords.contains(temp)) {
                    continue;
                }
                node = node.querySub(temp);
                if (node == null) {
                    break;
                }
                if (node.isLast()) {
                    couldMark = true;
                    markNum = k - i;
                }
                cpCurrC = temp;
            }
            if (couldMark) {
                for (k = 0; k <= markNum; k++) {
                    chs[k + i] = SIGN;
                }
                i = i + markNum;
            }
        }
        return new String(chs);
    }

    /**
     * 是否包含敏感词。
     */
    public boolean isContains(final String src) {
        if (src == null || src.isEmpty() || nodes.isEmpty()) {
            return false;
        }
        char[] chs = src.toCharArray();
        int length = chs.length;
        int currc;
        int cpcurrc;
        int k;
        WordNode node;
        for (int i = 0; i < length; i++) {
            currc = charConvert(chs[i]);
            if (!set.contains(currc)) {
                continue;
            }
            node = nodes.get(currc);
            if (node == null) {
                continue;
            }
            boolean couldMark = node.isLast();
            k = i;
            cpcurrc = currc;
            while (++k < length) {
                int temp = charConvert(chs[k]);
                if (temp == cpcurrc) {
                    continue;
                }
                if (stopWords.contains(temp)) {
                    continue;
                }
                node = node.querySub(temp);
                if (node == null) {
                    break;
                }
                if (node.isLast()) {
                    couldMark = true;
                }
                cpcurrc = temp;
            }
            if (couldMark) {
                return true;
            }
        }
        return false;
    }

    /** 是否未配置任何敏感词（此时 doFilter/isContains 均为直通） */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** 大写转小写、全角转半角 */
    private static int charConvert(char src) {
        int r = BCConvert.qj2bj(src);
        return (r >= 'A' && r <= 'Z') ? r + 32 : r;
    }
}
