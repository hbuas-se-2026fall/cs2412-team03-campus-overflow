package com.campusoverflow.shared.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PageRequest} 与 {@link PageResult} 的边界测试。
 *
 * <p>分页参数是**用户可控的输入**，所有边界都必须由记录自身的紧凑构造器兜住：
 * {@code page=0}、{@code size=-1}、{@code size=100000} 都会从 query string 直接进来。
 * 这里没有校验的话，下游要么查出全表，要么抛 SQL 异常。
 */
class PageRequestTest {

    @Test
    @DisplayName("正常参数原样保留")
    void keepsValidValues() {
        PageRequest req = new PageRequest(2, 20);

        assertThat(req.page()).isEqualTo(2);
        assertThat(req.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("page 小于 1 被夹到 1，不会出现负数 offset")
    void clampsPageToAtLeastOne() {
        // page=0 时 offset 会是 -size，直接拼进 SQL 就是语法错误。
        assertThat(new PageRequest(0, 10).page()).isEqualTo(1);
        assertThat(new PageRequest(-5, 10).page()).isEqualTo(1);
        assertThat(new PageRequest(0, 10).offset()).isZero();
    }

    @Test
    @DisplayName("size 小于 1 被夹到 1")
    void clampsSizeToAtLeastOne() {
        // size=0 会导致 LIMIT 0 永远返回空，用户以为没数据。
        assertThat(new PageRequest(1, 0).size()).isEqualTo(1);
        assertThat(new PageRequest(1, -3).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("size 超过上限被夹到 50，不会拖垮数据库")
    void clampsSizeToMax() {
        // 这是防「?size=1000000 拖垮数据库」的唯一防线。
        assertThat(new PageRequest(1, 1000).size()).isEqualTo(PageRequest.MAX_SIZE);
        assertThat(new PageRequest(1, 51).size()).isEqualTo(50);
        assertThat(new PageRequest(1, 50).size()).isEqualTo(50);   // 边界值本身放行
    }

    @Test
    @DisplayName("offset 计算正确")
    void computesOffset() {
        assertThat(new PageRequest(1, 10).offset()).isZero();
        assertThat(new PageRequest(2, 10).offset()).isEqualTo(10);
        assertThat(new PageRequest(3, 20).offset()).isEqualTo(40);
    }

    @Test
    @DisplayName("page 极大时 offset 溢出为负数——这是一个真实缺陷")
    void offsetOverflowsOnHugePage() {
        // 已知问题（本测试用断言把它固定下来，便于日后修复时发现）：
        // (page - 1) * size 用的是 int 运算，page = Integer.MAX_VALUE 时溢出成负数，
        // 拼进 SQL 会得到 OFFSET -20 这样的非法值。
        //
        // 当前实现的夹取只处理了下界，没有处理上界。
        // 修复建议：在紧凑构造器里把 page 上限夹到 Integer.MAX_VALUE / MAX_SIZE，
        // 或者 offset() 返回 long。
        PageRequest huge = new PageRequest(Integer.MAX_VALUE, 50);

        assertThat(huge.offset()).isNegative();   // 现状：溢出
        // 一旦有人修好这个缺陷，下面这行会失败，提醒把断言反过来。
    }

    @Test
    @DisplayName("MAX_SIZE 是 50")
    void maxSizeIsFifty() {
        assertThat(PageRequest.MAX_SIZE).isEqualTo(50);
    }
}

/**
 * {@link PageResult} 的测试：不可变性、映射与总页数。
 */
class PageResultTest {

    @Test
    @DisplayName("items 被复制成不可变列表，外部改动影响不到结果")
    void itemsAreImmutableCopy() {
        // 如果直接持有调用方传进来的 list，调用方后续 add 会让「已返回的分页结果」变化，
        // 这类 bug 出现在「同一个 list 被复用两次查询」时，非常难查。
        List<String> source = new ArrayList<>(List.of("a", "b"));
        PageResult<String> result = new PageResult<>(source, 2, 1, 10);

        source.add("c");

        assertThat(result.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> result.items().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("empty() 给出空列表与 total=0")
    void emptyFactory() {
        PageResult<String> empty = PageResult.empty(1, 20);

        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isZero();
        assertThat(empty.page()).isEqualTo(1);
        assertThat(empty.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("empty() 在越界页上也能用——前端翻到最后一页之后不该报错")
    void emptyOnOutOfRangePage() {
        PageResult<String> empty = PageResult.empty(99, 20);

        assertThat(empty.items()).isEmpty();
        assertThat(empty.page()).isEqualTo(99);
    }

    @Test
    @DisplayName("map 转换元素但保留分页元数据")
    void mapKeepsPaginationMetadata() {
        PageResult<Integer> src = new PageResult<>(List.of(1, 2, 3), 42, 2, 10);

        PageResult<String> mapped = src.map(i -> "第" + i + "条");

        assertThat(mapped.items()).containsExactly("第1条", "第2条", "第3条");
        assertThat(mapped.total()).isEqualTo(42);
        assertThat(mapped.page()).isEqualTo(2);
        assertThat(mapped.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("map 之后仍然不可变")
    void mapResultIsImmutable() {
        PageResult<String> mapped = new PageResult<>(List.of(1, 2), 2, 1, 10).map(String::valueOf);

        assertThatThrownBy(() -> mapped.items().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("totalPages 向上取整")
    void totalPagesRoundsUp() {
        // 41 条 / 每页 10 → 5 页，不是 4 页。写成 total / size 会少一页，
        // 前端就永远看不到最后一条。
        assertThat(new PageResult<>(List.of(), 41, 1, 10).totalPages()).isEqualTo(5);
        assertThat(new PageResult<>(List.of(), 40, 1, 10).totalPages()).isEqualTo(4);
        assertThat(new PageResult<>(List.of(), 1, 1, 10).totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("total=0 时 totalPages 是 0")
    void totalPagesZero() {
        assertThat(new PageResult<>(List.of(), 0, 1, 10).totalPages()).isZero();
    }

    @Test
    @DisplayName("size=0 时 totalPages 返回 0 而不是抛除零异常")
    void totalPagesHandlesZeroSize() {
        // 虽然 PageRequest 已保证 size >= 1，但 PageResult 是独立 record，
        // 可以被直接构造出来（比如从缓存反序列化），必须自己防除零。
        assertThat(new PageResult<>(List.of(), 100, 1, 0).totalPages()).isZero();
    }

    @Test
    @DisplayName("total 很大时不会溢出")
    void totalPagesWithLargeTotal() {
        assertThat(new PageResult<>(List.of(), 1_000_000, 1, 50).totalPages()).isEqualTo(20_000);
    }
}
