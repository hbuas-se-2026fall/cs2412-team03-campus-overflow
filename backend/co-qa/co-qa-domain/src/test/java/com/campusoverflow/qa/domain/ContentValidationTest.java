package com.campusoverflow.qa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 正文与标签的校验边界。这两处是「发帖失败」最常见的两个原因，
 * 但之前只测了正常路径——`null`、纯空白、长度上下限、`#` 前缀、大小写去重这些分支都没走过。
 */
class ContentValidationTest {

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    void 问题正文至少二十个字符() {
        assertThat(MarkdownBody.ofQuestion(repeat("红", 20)).value()).hasSize(20);
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(repeat("红", 19)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("20-30000")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1002");
    }

    @Test
    void 回答正文至少十个字符() {
        assertThat(MarkdownBody.ofAnswer(repeat("白", 10)).value()).hasSize(10);
        assertThatThrownBy(() -> MarkdownBody.ofAnswer(repeat("白", 9)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1002");
    }

    @Test
    void 正文为null或纯空白时拒绝() {
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MarkdownBody.ofQuestion("                \n\t      "))   // 20 个空白
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MarkdownBody.ofAnswer(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MarkdownBody.ofAnswer("            "))                    // 12 个空格
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 正文超过三万个字符时拒绝() {
        assertThat(MarkdownBody.ofQuestion(repeat("红", 30000)).value()).hasSize(30000);
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(repeat("红", 30001)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1002");
    }

    @Test
    void 正文按码点计数而不是按char计数() {
        // emoji 是 1 个码点但 2 个 char；20 个 emoji 应当刚好通过（若按 char 计就是 40）
        String emoji = "🌳";
        assertThat(MarkdownBody.ofQuestion(emoji.repeat(20)).value()).isNotNull();
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(emoji.repeat(19)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 标签去掉井号与首尾空白并统一小写() {
        Tags tags = Tags.of(List.of("#数据结构", "  红黑树 ", "JAVA"));

        assertThat(tags.names()).containsExactly("数据结构", "红黑树", "java");
    }

    @Test
    void 标签去重且最多五个() {
        Tags tags = Tags.of(List.of("红黑树", "红黑树", "AVL", "平衡", "旋转", "插入"));

        assertThat(tags.names()).containsExactly("红黑树", "avl", "平衡", "旋转", "插入");
        assertThatThrownBy(() -> Tags.of(List.of("a", "b", "c", "d", "e", "f")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("最多 5 个")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1005");
    }

    @Test
    void 标签为空或全是空白时拒绝() {
        assertThatThrownBy(() -> Tags.of(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Tags.of(List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Tags.of(Arrays.asList(null, "  ", "")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1005");
    }

    @Test
    void 只有一个标签是允许的() {
        assertThat(Tags.of(List.of("数据结构")).names()).containsExactly("数据结构");
    }
}
