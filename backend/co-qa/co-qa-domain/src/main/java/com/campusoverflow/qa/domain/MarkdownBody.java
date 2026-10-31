package com.campusoverflow.qa.domain;

import com.campusoverflow.shared.domain.Guard;
import java.util.Objects;

/** Markdown 正文。只保存原文；渲染与 XSS 净化在基础设施层完成。 */
public record MarkdownBody(String value) {

    /** 正文最短 10 字，最长 20000 字（列类型 MEDIUMTEXT，长度上限按可读性收紧）。 */
    public static final int MIN = 10;
    public static final int MAX = 20000;

    public MarkdownBody {
        value = Objects.requireNonNull(value, "value");
    }

    public static MarkdownBody ofQuestion(String raw) {
        return new MarkdownBody(Guard.requireText(raw, MIN, MAX, "QA-1002", "问题正文"));
    }

    public static MarkdownBody ofAnswer(String raw) {
        return new MarkdownBody(Guard.requireText(raw, MIN, MAX, "QA-1002", "回答正文"));
    }
}
