package com.example.aiworkshop.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * LlmService 单元测试
 *
 * <p>测试 {@link LlmService#generate(String)} 和 {@link LlmService#generateJson(String)} 方法的正确性。</p>
 */
@SpringBootTest(classes = LlmService.class)
public class LlmServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LlmServiceTest.class);

    @Autowired
    private LlmService llmService;

    /**
     * 测试 generate 方法：返回原始文本，验证思考模式已开启
     */
    @Test
    public void testGenerate() {
        log.info("========== 测试 LlmService.generate ==========");

        // 提示词：普通的问答
        String prompt = "请用一句话介绍Java编程语言的特点。";

        // 调用 generate 方法
        String response = llmService.generate(prompt);

        log.info("generate 返回:\n{}", response);
        log.info("响应长度: {} 字符", response != null ? response.length() : 0);

        // 验证结果不为空
        Assertions.assertNotNull(response, "响应不应为空");
        Assertions.assertFalse(response.isEmpty(), "响应不应为空字符串");
        Assertions.assertTrue(response.length() > 10, "响应内容太短，可能有问题");

        // 验证返回的是普通文本（包含Java相关内容）
        Assertions.assertTrue(response.contains("Java") || response.contains("java")
                        || response.contains("面向对象") || response.contains("跨平台"),
                "响应应包含Java相关描述，实际内容: " + response);

        log.info("========== testGenerate 测试通过 ==========");
    }

    /**
     * 测试 generateJson 方法：要求模型返回 JSON 格式数据
     */
    @Test
    public void testGenerateJson() {
        log.info("========== 测试 LlmService.generateJson ==========");

        // 提示词：要求模型返回固定格式的 JSON
        String prompt = "请生成一个用户的JSON信息，包含name(姓名)、age(年龄)、city(城市)三个字段，" +
                "要求使用中文姓名和真实城市，只返回JSON，不要任何额外文字。\n" +
                "示例格式：{\"name\":\"李四\",\"age\":25,\"city\":\"北京\"}";

        // 调用 generateJson 方法
        String response = llmService.generateJson(prompt);

        log.info("generateJson 返回:\n{}", response);
        log.info("响应长度: {} 字符", response != null ? response.length() : 0);

        // 验证结果不为空
        Assertions.assertNotNull(response, "响应不应为空");
        Assertions.assertFalse(response.isEmpty(), "响应不应为空字符串");

        // 验证返回的是合法的 JSON
        try {
            JSONObject json = JSON.parseObject(response);
            Assertions.assertTrue(json.containsKey("name"), "返回的JSON应包含name字段");
            Assertions.assertTrue(json.containsKey("age"), "返回的JSON应包含age字段");
            Assertions.assertTrue(json.containsKey("city"), "返回的JSON应包含city字段");

            log.info("解析结果 - name: {}, age: {}, city: {}",
                    json.getString("name"), json.getInteger("age"), json.getString("city"));
        } catch (Exception e) {
            Assertions.fail("返回内容不是合法的JSON格式: " + response);
        }

        log.info("========== testGenerateJson 测试通过 ==========");
    }
}
