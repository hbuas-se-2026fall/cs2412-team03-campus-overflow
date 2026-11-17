package com.campusoverflow.discovery.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 索引与检索结果两个记录类型：标签必须做防御性拷贝，否则调用方改列表会悄悄改掉索引数据。 */
class SearchRecordsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void 搜索文档对标签做防御性拷贝() {
        List<String> tags = new ArrayList<>(List.of("数据结构", "红黑树"));
        SearchDocument doc = new SearchDocument(1L, 7L, 9L, "红黑树插入失衡", "正文", tags, NOW);

        tags.add("被外部追加的标签");

        assertThat(doc.tags()).containsExactly("数据结构", "红黑树");
        assertThatThrownBy(() -> doc.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 搜索文档字段原样保留且课程可空() {
        SearchDocument doc = new SearchDocument(1L, null, 9L, "标题", "正文", List.of(), NOW);

        assertThat(doc.questionId()).isEqualTo(1L);
        assertThat(doc.courseId()).isNull();
        assertThat(doc.authorId()).isEqualTo(9L);
        assertThat(doc.title()).isEqualTo("标题");
        assertThat(doc.bodyText()).isEqualTo("正文");
        assertThat(doc.tags()).isEmpty();
        assertThat(doc.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 搜索文档的标签不能为null() {
        assertThatThrownBy(() -> new SearchDocument(1L, null, 9L, "标题", "正文", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 检索命中结果携带排序与徽标所需的全部字段() {
        SearchHit hit = new SearchHit(42L, "红黑树插入失衡怎么调整", "……旋转逻辑写错……",
                List.of("数据结构", "红黑树"), 7L, 9L, 88, 3, true, true, NOW);

        assertThat(hit.questionId()).isEqualTo(42L);
        assertThat(hit.title()).contains("红黑树");
        assertThat(hit.snippet()).contains("旋转");
        assertThat(hit.tags()).containsExactly("数据结构", "红黑树");
        assertThat(hit.courseId()).isEqualTo(7L);
        assertThat(hit.authorId()).isEqualTo(9L);
        assertThat(hit.score()).isEqualTo(88);
        assertThat(hit.answerCount()).isEqualTo(3);
        assertThat(hit.accepted()).isTrue();
        assertThat(hit.bountyOpen()).isTrue();
        assertThat(hit.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 未采纳未悬赏的命中结果两个标志位都是false() {
        SearchHit hit = new SearchHit(1L, "标题", "片段", List.of(), null, 9L, 0, 0, false, false, NOW);

        assertThat(hit.accepted()).isFalse();
        assertThat(hit.bountyOpen()).isFalse();
        assertThat(hit.courseId()).isNull();
    }
}
