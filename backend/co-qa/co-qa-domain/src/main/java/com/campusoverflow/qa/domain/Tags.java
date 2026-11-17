package com.campusoverflow.qa.domain;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.util.Collection;
import java.util.List;

/** 问题的标签集合。1..5 个，保存时统一转小写并去重；空集合或超限均抛业务异常。 */
public record Tags(List<String> names) {

    // 每个问题最多 5 个标签（去重后的有效标签数）
    private static final int MAX = 5;

    /**
     * 工厂方法：创建 Tags 对象，并在构造时完成数据清洗（去 #、小写、去重）。
     *
     * <p>清洗后的数量必须落在 1..MAX，越界抛 {@link BusinessRuleException}。
     * 规格由 {@code QuestionTest.Posting.requiresOneToFiveTags} 固化：超过 5 个
     * 标签是非法输入，不能被 .limit(MAX) 静默截断——那会让用户以为 6 个标签
     * 都保存成功了。按团队章程「红测试是规格说明」，此处修实现而不是改测试。
     */
    public static Tags of(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1-" + MAX + " 个标签");
        }

        // 过滤空值 -> 去 # 号 -> 去空格 -> 转小写 -> 去重
        List<String> cleaned = raw.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.startsWith("#") ? t.substring(1) : t)
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .toList();

        if (cleaned.isEmpty()) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1-" + MAX + " 个标签");
        }
        if (cleaned.size() > MAX) {
            throw new BusinessRuleException("QA-1005",
                    "每个问题最多 " + MAX + " 个标签，实际 " + cleaned.size() + " 个");
        }

        return new Tags(cleaned);
    }
}