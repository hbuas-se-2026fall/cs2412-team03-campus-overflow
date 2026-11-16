package com.campusoverflow.discovery.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @提及解析：通知中心的入口。原有 {@code DiscoveryDomainTest} 只覆盖了「去重 + 上限 5」，
 * 这里补空输入、长度边界与标点边界——正则 {@code {2,20}} 的这两条边界决定了「@张老师，」能不能被识别。
 */
class MentionParserTest {

    @Test
    void 空文本与空白文本返回空集合() {
        assertThat(MentionParser.extract(null)).isEmpty();
        assertThat(MentionParser.extract("")).isEmpty();
        assertThat(MentionParser.extract("   \n\t ")).isEmpty();
    }

    @Test
    void 没有提及符号时返回空集合() {
        assertThat(MentionParser.extract("这道题我也不会，等老师讲")).isEmpty();
        assertThat(MentionParser.extract("邮箱 a@b.c 不算提及")).isEmpty();
    }

    @Test
    void 提及后面紧跟中文标点也能正确截断() {
        assertThat(MentionParser.extract("感谢 @王老师，问题解决了")).containsExactly("王老师");
        assertThat(MentionParser.extract("@张助教。麻烦看一下")).containsExactly("张助教");
        assertThat(MentionParser.extract("问 @李同学：这行代码什么意思")).containsExactly("李同学");
    }

    @Test
    void 单个字符不算提及因为昵称最短两位() {
        assertThat(MentionParser.extract("@x 你好")).isEmpty();
        assertThat(MentionParser.extract("@小明 你好")).containsExactly("小明");
    }

    @Test
    void 保留首次出现的顺序而不是字典序() {
        assertThat(MentionParser.extract("@丙丙 @甲甲 @乙乙")).containsExactly("丙丙", "甲甲", "乙乙");
    }

    @Test
    void 超过五个提及只取前五个() {
        assertThat(MentionParser.extract("@aa @bb @cc @dd @ee @ff @gg"))
                .containsExactly("aa", "bb", "cc", "dd", "ee");
        assertThat(MentionParser.MAX_MENTIONS).isEqualTo(5);
    }

    @Test
    void 同一段文本里重复提及只保留一次() {
        assertThat(MentionParser.extract("@王老师 和 @王老师 都说可以")).containsExactly("王老师");
    }
}
