package com.example.aiworkshop.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * LLM交互服务类
 *
 * <p>提供与DeepSeek等大语言模型交互的通用方法，支持思考模式。</p>
 */
@Service
@Slf4j
public class LlmService {

    @Value("${llm.api-key:sk-xxx}")
    private String apiKey;

    @Value("${llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${llm.model:deepseek-v4-flash}")
    private String model;

    @Value("${llm.reasoning-effort:high}")
    private String reasoningEffort;

    private String chatEndpoint;

    private CloseableHttpClient httpClient;

    @PostConstruct
    public void init() {
        log.info("========== LlmService 初始化 ==========");
        log.info("API Key: {}", apiKey != null && !apiKey.isEmpty() ? "已配置" : "未配置");
        log.info("Base URL: {}", baseUrl);
        log.info("Model: {}", model);
        log.info("Reasoning Effort: {}", reasoningEffort);

        // 拼接聊天补全接口地址
        String url = baseUrl;
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "v1/chat/completions";
        this.chatEndpoint = url;

        this.httpClient = HttpClients.createDefault();

        log.info("LlmService初始化完成，聊天接口: {}", chatEndpoint);
    }

    /**
     * 调用LLM模型获取响应（启用思考模式）
     *
     * @param prompt 提示词
     * @return LLM返回的响应字符串
     */
    public String generate(String prompt) {
        log.info("========== 调用LLM生成响应(思考模式) ==========");
        log.info("Prompt长度: {} 字符", prompt != null ? prompt.length() : 0);

        try {
            String response = callDeepSeek(prompt);
            log.info("响应长度: {} 字符", response != null ? response.length() : 0);
            log.info("响应内容: {}", response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);
            return response;
        } catch (Exception e) {
            log.error("LLM调用失败", e);
            throw new RuntimeException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用LLM模型获取JSON格式响应（启用思考模式）
     *
     * @param prompt 提示词（应包含要求JSON格式输出的指令）
     * @return LLM返回的JSON字符串
     */
    public String generateJson(String prompt) {
        log.info("========== 调用LLM生成JSON响应(思考模式) ==========");
        String response = generate(prompt);

        // 尝试清理响应，提取纯JSON
        if (response != null) {
            response = response.trim();
            if (response.startsWith("```json")) {
                response = response.substring(7);
            } else if (response.startsWith("```")) {
                response = response.substring(3);
            }
            if (response.endsWith("```")) {
                response = response.substring(0, response.length() - 3);
            }
            response = response.trim();
        }

        log.info("清理后的JSON响应: {}", response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);
        return response;
    }

    /**
     * 调用DeepSeek API（启用思考模式）
     *
     * @param prompt 用户提示词
     * @return 模型返回的文本内容
     */
    private String callDeepSeek(String prompt) throws IOException {
        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);

        // 构建消息列表
        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        // 启用思考模式
        requestBody.put("reasoning_effort", reasoningEffort);
        JSONObject thinking = new JSONObject();
        thinking.put("type", "enabled");
        requestBody.put("thinking", thinking);

        String jsonBody = JSON.toJSONString(requestBody);
        log.info("请求体: {}", jsonBody);

        // 创建HTTP POST请求
        HttpPost httpPost = new HttpPost(chatEndpoint);
        httpPost.setHeader("Content-Type", "application/json; charset=UTF-8");
        httpPost.setHeader("Authorization", "Bearer " + apiKey);
        httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));

        // 发送请求
        try (CloseableHttpResponse httpResponse = httpClient.execute(httpPost)) {
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(httpResponse.getEntity(), StandardCharsets.UTF_8);

            if (statusCode != 200) {
                log.error("API调用失败, 状态码: {}, 响应: {}", statusCode, responseBody);
                throw new RuntimeException("API调用失败, 状态码: " + statusCode + ", 响应: " + responseBody);
            }

            // 解析响应
            JSONObject responseJson = JSON.parseObject(responseBody);
            JSONArray choices = responseJson.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("API返回的choices为空: {}", responseBody);
                throw new RuntimeException("API返回的choices为空");
            }

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message.getString("content");

            // 记录思考过程（如果有）
            String reasoningContent = message.getString("reasoning_content");
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                log.info("思考过程长度: {} 字符", reasoningContent.length());
                log.info("思考过程: {}", reasoningContent);
            }

            return content;
        }
    }
}
