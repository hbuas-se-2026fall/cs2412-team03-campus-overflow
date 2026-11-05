package com.campusoverflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Lab7 / #34 的验收用例：Testcontainers 起真实 MySQL 8 + Redis 7，
 * 走完「注册 → 登录 → 提问 → 回答 → 采纳」的 Controller → Service → Repository 全链路，
 * 然后用 JdbcTemplate 直查数据库，验证落库结果，而不是只靠 HTTP 响应间接推断。
 *
 * <p>与 {@link CampusOverflowFlowIT} 的区别：本用例只覆盖 #34 的三条验收标准，
 * 刻意不依赖声誉结算、搜索投影、通知等 S3 业务实现（那些目前仍是骨架 TODO）。
 * 完整端到端链路作为 S3 规格由 CampusOverflowFlowIT 保留，待实现补齐后一并转绿。
 *
 * <p>测试不依赖任何本机服务：数据源与 Redis 均由 @ServiceConnection 注入容器地址。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
        "spring.flyway.clean-disabled=false"
})
class QuestionPersistenceIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.39"))
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--ngram-token-size=2");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 提问回答采纳全链路把正确数据写入真实MySQL容器() throws Exception {
        long mingId = register("2026101", "持久化小明", "persist-ming@campus.edu.cn");
        long hongId = register("2026102", "持久化小红", "persist-hong@campus.edu.cn");
        MockHttpSession ming = login("2026101");
        MockHttpSession hong = login("2026102");

        long questionId = idOf(mvc.perform(post("/api/v1/questions").session(ming).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"B+树与LSM树的写入放大有什么区别？",
                                 "body":"在日志型写入场景下，两种结构的写放大来源不同，想弄清楚各自的代价与适用边界。",
                                 "tags":["数据库","索引"]}
                                """))
                .andExpect(status().isCreated()).andReturn());

        // 问题与标签确实落库（含跨上下文的 author_id 逻辑引用）
        assertThat(jdbc.queryForObject(
                "SELECT title FROM qa_question WHERE id = ?", String.class, questionId))
                .isEqualTo("B+树与LSM树的写入放大有什么区别？");
        assertThat(jdbc.queryForObject(
                "SELECT author_id FROM qa_question WHERE id = ?", Long.class, questionId))
                .isEqualTo(mingId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_question_tag WHERE question_id = ?", Integer.class, questionId))
                .isEqualTo(2);

        long answerId = idOf(mvc.perform(post("/api/v1/questions/" + questionId + "/answers").session(hong)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"B+树是原地更新，写放大主要来自页分裂与脏页刷盘；LSM 是追加写，代价转移到后台合并压实。"}
                                """))
                .andExpect(status().isCreated()).andReturn());

        assertThat(jdbc.queryForObject(
                "SELECT question_id FROM qa_answer WHERE id = ?", Long.class, answerId))
                .isEqualTo(questionId);
        assertThat(jdbc.queryForObject(
                "SELECT author_id FROM qa_answer WHERE id = ?", Long.class, answerId))
                .isEqualTo(hongId);
        assertThat(jdbc.queryForObject(
                "SELECT answer_count FROM qa_question WHERE id = ?", Integer.class, questionId))
                .isEqualTo(1);

        mvc.perform(put("/api/v1/questions/" + questionId + "/accepted-answer").session(ming).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answerId\":" + answerId + "}"))
                .andExpect(status().isNoContent());

        // 采纳状态持久化，而不是只改了内存里的聚合
        assertThat(jdbc.queryForObject(
                "SELECT accepted_answer_id FROM qa_question WHERE id = ?", Long.class, questionId))
                .isEqualTo(answerId);

        // 用户敏感字段以密文落库（ADR：邮箱加密 + HMAC 摘要），可间接验证 Flyway 迁移完整
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM id_user WHERE username IN ('2026101','2026102') "
                        + "AND email_cipher IS NOT NULL AND email_hash IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }

    private long register(String username, String displayName, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterPayload(username, displayName, email,
                                "Campus2026", "计算机工程学院", "计科2301"))))
                .andExpect(status().isCreated()).andReturn();
        return idOf(result);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Campus2026\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long idOf(MvcResult result) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    private record RegisterPayload(String username, String displayName, String email, String password,
                                   String college, String className) {
    }
}
