package org.caps.columbo.types.utils.sensitive;

/**
 * 全角/半角转换：DFA 匹配前把「全角字符、大写字母」归一化为半角小写，防变形绕过。
 *
 * <p>提炼自 YL hospital-tongxing-all tx-utils 的 BCConvert。
 */
public final class BCConvert {

    /** ASCII 可见字符起始（半角 !） */
    static final char DBC_CHAR_START = 33;

    /** ASCII 可见字符结束（半角 ~） */
    static final char DBC_CHAR_END = 126;

    /** 全角可见字符起始（全角 ！） */
    static final char SBC_CHAR_START = 65281;

    /** 全角可见字符结束（全角 ～） */
    static final char SBC_CHAR_END = 65374;

    /** 全角与 ASCII 可见字符的相对偏移 */
    static final int CONVERT_STEP = 65248;

    /** 全角空格（不遵从相对偏移，单独处理） */
    static final char SBC_SPACE = 12288;

    /** 半角空格 */
    static final char DBC_SPACE = ' ';

    private BCConvert() {
    }

    /** 半角字符串 → 全角：只处理空格与 !~ 区间可见字符 */
    public static String bj2qj(String src) {
        if (src == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder(src.length());
        for (char c : src.toCharArray()) {
            buf.append((char) bj2qj(c));
        }
        return buf.toString();
    }

    /** 半角字符 → 全角字符值 */
    public static int bj2qj(char src) {
        if (src == DBC_SPACE) {
            return SBC_SPACE;
        }
        if (src >= DBC_CHAR_START && src <= DBC_CHAR_END) {
            return src + CONVERT_STEP;
        }
        return src;
    }

    /** 全角字符串 → 半角：只处理全角空格与全角 ！～ 区间字符 */
    public static String qj2bj(String src) {
        if (src == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder(src.length());
        for (char c : src.toCharArray()) {
            buf.append((char) qj2bj(c));
        }
        return buf.toString();
    }

    /** 全角字符 → 半角字符值 */
    public static int qj2bj(char src) {
        if (src >= SBC_CHAR_START && src <= SBC_CHAR_END) {
            return src - CONVERT_STEP;
        }
        if (src == SBC_SPACE) {
            return DBC_SPACE;
        }
        return src;
    }
}
