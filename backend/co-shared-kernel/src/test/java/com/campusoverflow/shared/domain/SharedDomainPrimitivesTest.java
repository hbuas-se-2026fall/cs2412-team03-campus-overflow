package com.campusoverflow.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 共享内核的领域原语契约。这些类被四个限界上下文共同依赖，但此前只有 Masking 有测试，
 * 覆盖率 10%。这里的断言固化的是「规格」而不是「实现细节」：异常码、建议状态码、
 * 文本长度按 Unicode 码点计算、清洗后返回 trim 值。
 */
class SharedDomainPrimitivesTest {

    @Test
    void guardReturnsTrimmedTextWithinRange() {
        assertThat(Guard.requireText("  Spring Boot  ", 1, 20, "QA-1001", "标题"))
                .isEqualTo("Spring Boot");
    }

    @Test
    void guardRejectsNullBlankAndOutOfRangeText() {
        assertThatThrownBy(() -> Guard.requireText(null, 1, 20, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("标题不能为空")
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("QA-1001"));

        assertThatThrownBy(() -> Guard.requireText("   ", 1, 20, "QA-1001", "标题"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("标题不能为空");

        assertThatThrownBy(() -> Guard.requireText("ab", 3, 20, "QA-1001", "标题"))
                .hasMessageContaining("标题长度应在 3–20 个字符之间（当前 2）");

        assertThatThrownBy(() -> Guard.requireText("a".repeat(21), 1, 20, "QA-1001", "标题"))
                .hasMessageContaining("当前 21");
    }

    @Test
    void guardCountsUnicodeCodePointsRatherThanUtf16Chars() {
        // 每个 emoji 占 1 个码点、2 个 char；按码点计长才符合「字符数」规格。
        assertThat(Guard.requireText("😀😀😀", 1, 3, "QA-1001", "正文")).isEqualTo("😀😀😀");
        assertThatThrownBy(() -> Guard.requireText("😀😀😀😀", 1, 3, "QA-1001", "正文"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("当前 4");
    }

    @Test
    void guardRejectsNullReferenceAndReturnsNonNullValue() {
        assertThatThrownBy(() -> Guard.requireNonNull(null, "QA-1002", "作者"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("作者不能为空");
        assertThat(Guard.requireNonNull("value", "QA-1002", "作者")).isEqualTo("value");
    }

    @Test
    void domainExceptionsExposeCodeMessageAndSuggestedStatus() {
        assertContract(new BusinessRuleException("QA-1005", "标签超限"), "QA-1005", "标签超限", 422);
        assertContract(new ConflictException("QA-3001", "重复采纳"), "QA-3001", "重复采纳", 409);
        assertContract(new ForbiddenException("QA-2001", "无权编辑"), "QA-2001", "无权编辑", 403);
        assertContract(new NotFoundException("QA-4001", "问题不存在"), "QA-4001", "问题不存在", 404);
    }

    @Test
    void domainExceptionBaseClassKeepsCodeForInterfaceLayer() {
        DomainException anonymous = new DomainException("DIS-0001", "自定义") {
            @Override
            public int suggestedStatus() {
                return 418;
            }
        };
        assertThat(anonymous.code()).isEqualTo("DIS-0001");
        assertThat(anonymous.getMessage()).isEqualTo("自定义");
        assertThat(anonymous.suggestedStatus()).isEqualTo(418);
        assertThat(anonymous).isInstanceOf(RuntimeException.class);
    }

    private static void assertContract(DomainException ex, String code, String message, int status) {
        assertThat(ex.code()).isEqualTo(code);
        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.suggestedStatus()).isEqualTo(status);
    }
}
