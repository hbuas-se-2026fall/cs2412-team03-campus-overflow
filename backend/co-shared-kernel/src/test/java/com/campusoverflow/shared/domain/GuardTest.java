package com.campusoverflow.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Guard} 的行为测试。
 *
 * <p>这个类被所有上下文的聚合根调用，是「非法数据进不了领域」的唯一闸门。
 * 它有一个容易忽略的细节：长度**按 Unicode 码点计**而不是按 {@code String.length()}。
 * 这个区别在中文和 emoji 上不显现（BMP 内字符 length 与码点一致），
 * 但遇到 emoji（代理对，占 2 个 char）时，按 char 计会算成两倍长度，
 * 用户明明只打了 10 个 emoji 却被拒绝——这类 bug 上线后极难复现。
 */
class GuardTest {

    // ---------- requireText ----------

    @Test
    @DisplayName("合法文本返回去除首尾空白后的值")
    void returnsTrimmedValue() {
        assertThat(Guard.requireText("  标题  ", 1, 10, "QA-1001", "标题")).isEqualTo("标题");
    }

    @Test
    @DisplayName("null 与空白抛 BusinessRuleException，且带上字段名")
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> Guard.requireText(null, 1, 10, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("标题不能为空");

        assertThatThrownBy(() -> Guard.requireText("   ", 1, 10, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("标题不能为空");
    }

    @Test
    @DisplayName("空白判定用 isBlank 而不是 isEmpty——全角空格也算空")
    void blankUsesUnicodeAwareCheck() {
        // String.isEmpty() 对 "　"（全角空格）返回 false，isBlank() 返回 true。
        // 用错方法的后果：用户输入一串全角空格就能建出标题为空的问题。
        assertThatThrownBy(() -> Guard.requireText("\u3000\u3000", 1, 10, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("标题不能为空");
    }

    @Test
    @DisplayName("长度按码点计：emoji 算 1 个字符，不是 2 个")
    void lengthCountsCodePointsNotChars() {
        // "😀" 是代理对，String.length() == 2，码点 == 1。
        // 若用 trimmed.length()，下面这个 10 个 emoji 的串会被算成 20 而误拒。
        String tenEmoji = "😀😀😀😀😀😀😀😀😀😀";
        assertThat(tenEmoji.length()).isEqualTo(20);   // 证明确实是代理对

        assertThat(Guard.requireText(tenEmoji, 1, 10, "QA-1001", "标题")).isEqualTo(tenEmoji);
    }

    @Test
    @DisplayName("超出最大长度要拒绝，且消息里带上实际长度")
    void rejectsTooLongWithActualLength() {
        assertThatThrownBy(() -> Guard.requireText("123456", 1, 5, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("当前 6");
    }

    @Test
    @DisplayName("短于最小长度要拒绝")
    void rejectsTooShort() {
        assertThatThrownBy(() -> Guard.requireText("ab", 3, 5, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("当前 2");
    }

    @Test
    @DisplayName("边界值本身要放行：正好 min 与正好 max")
    void acceptsBoundaryLengths() {
        // 最常见的 off-by-one：把 <= 写成 <
        assertThat(Guard.requireText("abc", 3, 5, "QA-1001", "标题")).hasSize(3);
        assertThat(Guard.requireText("abcde", 3, 5, "QA-1001", "标题")).hasSize(5);
    }

    @Test
    @DisplayName("首尾空白不计入长度")
    void whitespaceNotCounted() {
        // "  abc  " 的 length 是 7，strip 后是 3，应当通过 [3,5] 的校验。
        assertThat(Guard.requireText("  abc  ", 3, 5, "QA-1001", "标题")).isEqualTo("abc");
    }

    @Test
    @DisplayName("错误码原样传给异常")
    void carriesGivenCode() {
        // 错误码是前端的契约（各上下文用不同前缀），不能被工具类改写。
        assertThatThrownBy(() -> Guard.requireText(null, 1, 10, "REP-2001", "悬赏金额"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "REP-2001");
    }

    @Test
    @DisplayName("拒绝时抛的是 422 而不是 400")
    void suggests422() {
        // 长度不符是业务规则问题（用户应看到原因），不是请求格式错误。
        assertThatThrownBy(() -> Guard.requireText("", 1, 10, "QA-1001", "标题"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).suggestedStatus())
                .isEqualTo(422);
    }

    // ---------- requireNonNull ----------

    @Test
    @DisplayName("非 null 值原样返回")
    void returnsNonNull() {
        Object value = new Object();
        assertThat(Guard.requireNonNull(value, "QA-1001", "作者")).isSameAs(value);
    }

    @Test
    @DisplayName("null 抛异常并带字段名")
    void rejectsNonNullViolation() {
        assertThatThrownBy(() -> Guard.requireNonNull(null, "QA-1001", "作者"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("作者不能为空");
    }

    @Test
    @DisplayName("requireNonNull 也把错误码透传")
    void nonNullCarriesCode() {
        assertThatThrownBy(() -> Guard.requireNonNull(null, "DISC-3001", "目标"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "DISC-3001");
    }
}
