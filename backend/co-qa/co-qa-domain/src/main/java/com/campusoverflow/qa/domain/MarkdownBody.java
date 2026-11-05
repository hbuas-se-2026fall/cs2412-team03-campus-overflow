package com.campusoverflow.qa.domain;

import com.campusoverflow.shared.domain.Guard;
import com.campusoverflow.shared.domain.BusinessRuleException;

/** Markdown 正文。只保存原文；渲染与 XSS 净化在基础设施层完成。 */
public record MarkdownBody(String value) {

    public static com.campusoverflow.qa.domain.MarkdownBody ofQuestion(String raw) {
        // 校验问题正文：非空，且长度限制在 20 到 30000 字符之间（指导书 6.3.2）
        // 注意：请把 IDEA 自动补全提示的 Guard 方法的参数顺序核对一下
        if (raw == null || raw.isBlank() || raw.codePointCount(0, raw.length()) < 20 || raw.codePointCount(0, raw.length()) > 30000) {
            throw new BusinessRuleException("QA-1002", "问题正文长度必须在 20-30000 字符之间");
        }
        return new com.campusoverflow.qa.domain.MarkdownBody(raw);
    }

    public static com.campusoverflow.qa.domain.MarkdownBody ofAnswer(String raw) {
        // 校验回答正文：非空，且长度限制在 10 到 30000 字符之间
        if (raw == null || raw.isBlank() || raw.codePointCount(0, raw.length()) < 10 || raw.codePointCount(0, raw.length()) > 30000) {
            throw new BusinessRuleException("QA-1002", "回答正文长度必须在 10-30000 字符之间");
        }
        return new com.campusoverflow.qa.domain.MarkdownBody(raw);
    }
}