package org.caps.columbo.types.utils.sensitive;

import java.util.LinkedList;
import java.util.List;

/**
 * DFA 敏感词节点（WordFilter 内部实现）。
 *
 * <p>提炼自 YL hospital-tongxing-all tx-utils 的 WordNode，
 * 删除了原版自递归的 equals（会 StackOverflowError）。
 */
class WordNode {

    /** 节点名称（规范化后的字符值） */
    private final int value;

    /** 子节点 */
    private List<WordNode> subNodes;

    /** 是否敏感词末位 */
    private boolean last;

    WordNode(int value, boolean last) {
        this.value = value;
        this.last = last;
    }

    private WordNode addSubNode(WordNode subNode) {
        if (subNodes == null) {
            subNodes = new LinkedList<>();
        }
        subNodes.add(subNode);
        return subNode;
    }

    /** 有该子节点就返回（必要时升级 last 标记），没有则创建添加后返回 */
    WordNode addIfNoExist(int value, boolean last) {
        if (subNodes == null) {
            return addSubNode(new WordNode(value, last));
        }
        for (WordNode subNode : subNodes) {
            if (subNode.value == value) {
                if (!subNode.last && last) {
                    subNode.last = true;
                }
                return subNode;
            }
        }
        return addSubNode(new WordNode(value, last));
    }

    WordNode querySub(int value) {
        if (subNodes == null) {
            return null;
        }
        for (WordNode subNode : subNodes) {
            if (subNode.value == value) {
                return subNode;
            }
        }
        return null;
    }

    boolean isLast() {
        return last;
    }

    /** 标记为敏感词末位（该字单独成词时使用） */
    void markLast() {
        this.last = true;
    }
}
