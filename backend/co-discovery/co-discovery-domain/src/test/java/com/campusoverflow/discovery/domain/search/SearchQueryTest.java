package com.campusoverflow.discovery.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.page.PageRequest;
import org.junit.jupiter.api.Test;

/**
 * 搜索查询对象的构造校验：MySQL 全文索引的布尔运算符必须被清掉，
 * 单字词在 ngram(2) 下永远命中不了，所以直接拒掉——这两条是「搜不到」类问题的根因。
 */
class SearchQueryTest {

    private static SearchQuery query(String keyword) {
        return new SearchQuery(keyword, null, null, null, false, false, null);
    }

    @Test
    void 清掉布尔运算符与引号避免把用户输入当查询语法() {
        SearchQuery q = query("+红黑树 -旋转* \"插入\" ~优化 (AVL)");

        assertThat(q.keyword()).doesNotContain("+", "-", "*", "\"", "~", "(", ")");
        assertThat(q.terms()).containsExactly("红黑树", "旋转", "插入", "优化", "AVL");
    }

    @Test
    void 只有运算符的输入等价于没有关键词() {
        SearchQuery q = query("+-*~()");

        assertThat(q.keyword()).isNull();
        assertThat(q.terms()).isEmpty();
        assertThat(q.sort()).isEqualTo(SearchSort.NEWEST);
    }

    @Test
    void 单个字符的词被拒绝因为ngram最小2() {
        assertThatThrownBy(() -> query("树 红黑树"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("至少 2 个字符")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("DIS-1001");
    }

    @Test
    void 关键词超过50个字符被拒绝() {
        String tooLong = "红".repeat(51);

        assertThatThrownBy(() -> query(tooLong))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("DIS-1002");
    }

    @Test
    void 恰好50个字符可以接受() {
        assertThat(query("红".repeat(50)).keyword()).hasSize(50);
    }

    @Test
    void 词项去重并最多保留五个() {
        SearchQuery q = query("红黑树 红黑树 AVL AVL 旋转 平衡 插入 删除");

        assertThat(q.terms()).containsExactly("红黑树", "AVL", "旋转", "平衡", "插入");
    }

    @Test
    void 标签统一小写并去掉首尾空白() {
        assertThat(new SearchQuery(null, null, "  Java  ", null, false, false, null).tag()).isEqualTo("java");
        assertThat(new SearchQuery(null, null, "   ", null, false, false, null).tag()).isNull();
        assertThat(new SearchQuery(null, null, null, null, false, false, null).tag()).isNull();
    }

    @Test
    void 排序默认值取决于有没有关键词() {
        assertThat(query("红黑树").sort()).isEqualTo(SearchSort.RELEVANCE);
        assertThat(query(null).sort()).isEqualTo(SearchSort.NEWEST);
        assertThat(new SearchQuery(null, null, null, SearchSort.RELEVANCE, false, false, null).sort())
                .isEqualTo(SearchSort.NEWEST);
        assertThat(new SearchQuery("红黑树", null, null, SearchSort.HOT, false, false, null).sort())
                .isEqualTo(SearchSort.HOT);
    }

    @Test
    void 分页默认第一页二十条并保留显式分页() {
        assertThat(query("红黑树").page()).isEqualTo(new PageRequest(1, 20));
        assertThat(new SearchQuery("红黑树", null, null, null, false, false, new PageRequest(3, 5)).page())
                .isEqualTo(new PageRequest(3, 5));
    }

    @Test
    void 过滤条件原样保留() {
        SearchQuery q = new SearchQuery("红黑树", 7L, "数据结构", SearchSort.ACTIVE, true, true, null);

        assertThat(q.courseId()).isEqualTo(7L);
        assertThat(q.tag()).isEqualTo("数据结构");
        assertThat(q.sort()).isEqualTo(SearchSort.ACTIVE);
        assertThat(q.unansweredOnly()).isTrue();
        assertThat(q.bountyOnly()).isTrue();
    }
}
