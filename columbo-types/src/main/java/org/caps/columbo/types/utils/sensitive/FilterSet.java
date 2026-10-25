package org.caps.columbo.types.utils.sensitive;

/**
 * char 位图集合：用 long 数组枚举 0~65535 的字符是否存在，作为 DFA 首字快速筛选。
 *
 * <p>提炼自 YL hospital-tongxing-all tx-utils 的 FilterSet，
 * 删除了会破坏内部状态的 containsAllUesLessWay（该方法直接替换了 elements 数组）。
 */
class FilterSet {

    private final long[] elements = new long[1 + (65535 >>> 6)];

    void add(int no) {
        elements[no >>> 6] |= (1L << (no & 63));
    }

    void remove(int no) {
        elements[no >>> 6] &= ~(1L << (no & 63));
    }

    /** @return true 表示本次添加成功，false 表示原已包含 */
    boolean addAndNotify(int no) {
        int wordNum = no >>> 6;
        long old = elements[wordNum];
        elements[wordNum] |= (1L << (no & 63));
        return elements[wordNum] != old;
    }

    /** @return true 表示本次移除成功，false 表示原本就不包含 */
    boolean removeAndNotify(int no) {
        int wordNum = no >>> 6;
        long old = elements[wordNum];
        elements[wordNum] &= ~(1L << (no & 63));
        return elements[wordNum] != old;
    }

    boolean contains(int no) {
        return (elements[no >>> 6] & (1L << (no & 63))) != 0;
    }

    int size() {
        int size = 0;
        for (long element : elements) {
            size += Long.bitCount(element);
        }
        return size;
    }
}
