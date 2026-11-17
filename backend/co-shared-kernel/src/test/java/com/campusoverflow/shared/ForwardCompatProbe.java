package com.campusoverflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 一个跟业务无关的最小正向用例：用来确认「develop 上的测试基础设施是可用的」。
 * 放开覆盖率门禁时顺带验证本地跑测试、CI 跑测试两条链路都通。
 */
class ForwardCompatProbe {

    @Test
    void 开发环境与测试依赖均已就绪() {
        List<String> modules = List.of("identity", "qa", "reputation", "discovery");
        assertThat(modules).hasSize(4).allMatch(m -> !m.isBlank());
    }
}
