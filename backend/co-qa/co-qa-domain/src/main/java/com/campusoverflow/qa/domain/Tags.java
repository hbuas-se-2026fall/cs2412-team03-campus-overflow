package com.campusoverflow.qa.domain;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.util.Collection;
import java.util.List;

/** 问题的标签集合。最多 5 个，保存时统一转小写并去重。 */
public record Tags(List<String> names) {

    // 限制每个问题最多 5 个标签
    private static final int MAX = 5;

    /**
     * 工厂方法：创建 Tags 对象，并在构造时完成数据清洗（去 #、小写、去重、限制数量）。
     */
    public static com.campusoverflow.qa.domain.Tags of(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1-" + MAX + " 个标签");
        }

        // 核心清洗逻辑：过滤空值 -> 去 # 号 -> 去空格 -> 转小写 -> 去重 -> 截取前 5 个
        List<String> cleaned = raw.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.startsWith("#") ? t.substring(1) : t)
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .limit(MAX)
                .toList();

        // 如果清洗完后发现一个合法标签都没有，也要报错
        if (cleaned.isEmpty()) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1-" + MAX + " 个标签");
        }

        // 注意：如果你在指导书里看到要用 TagName 进行单标签校验，可以在这里加上：
        // cleaned.forEach(TagName::new);

        return new com.campusoverflow.qa.domain.Tags(cleaned);
    }
}