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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Qwen多模态服务类
 *
 * <p>提供与Qwen3.7-plus多模态大模型交互的方法，支持图片和视频分析功能。</p>
 */
@Service
@Slf4j
public class QwenMultiModalService {

    @Value("${qwen.api-key:sk-66815fd5e13b4ff19485bb7f5112c1ba}")
    private String apiKey;

    @Value("${qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${qwen.model:qwen3.7-plus}")
    private String model;

    private String chatEndpoint;

    private CloseableHttpClient httpClient;

    @PostConstruct
    public void init() {
        log.info("========== QwenMultiModalService 初始化 ==========");
        log.info("API Key: {}", apiKey != null && !apiKey.isEmpty() ? "已配置" : "未配置");
        log.info("Base URL: {}", baseUrl);
        log.info("Model: {}", model);

        // 拼接聊天补全接口地址
        String url = baseUrl;
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "chat/completions";
        this.chatEndpoint = url;

        this.httpClient = HttpClients.createDefault();
        log.info("Qwen多模态服务初始化成功，聊天接口: {}", chatEndpoint);
    }

    /**
     * 调用Qwen多模态模型分析图片
     *
     * @param prompt    提示词
     * @param imagePath 图片文件路径
     * @return LLM返回的响应文本内容
     */
    public String analyzeImage(String prompt, String imagePath) {
        log.info("========== 调用Qwen多模态模型分析图片 ==========");
        log.info("提示词长度: {} 字符", prompt != null ? prompt.length() : 0);
        log.info("图片路径: {}", imagePath);

        try {
            // 读取图片并编码为Base64
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                throw new RuntimeException("图片文件不存在: " + imagePath);
            }

            byte[] imageBytes = new byte[(int) imageFile.length()];
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                fis.read(imageBytes);
            }
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUrl = "data:image/png;base64," + base64Image;

            log.info("图片Base64编码长度: {} 字符", base64Image.length());

            return callQwenMultimodal(prompt, "image_url", dataUrl);

        } catch (IOException e) {
            log.error("Qwen多模态模型调用失败", e);
            throw new RuntimeException("Qwen多模态模型调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用Qwen多模态模型分析视频
     *
     * @param prompt    提示词
     * @param videoPath 视频文件路径
     * @return LLM返回的响应文本内容
     */
    public String analyzeVideo(String prompt, String videoPath) {
        log.info("========== 调用Qwen多模态模型分析视频 ==========");
        log.info("提示词长度: {} 字符", prompt != null ? prompt.length() : 0);
        log.info("视频路径: {}", videoPath);

        try {
            // 读取视频并编码为Base64
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                throw new RuntimeException("视频文件不存在: " + videoPath);
            }

            byte[] videoBytes = new byte[(int) videoFile.length()];
            try (FileInputStream fis = new FileInputStream(videoFile)) {
                fis.read(videoBytes);
            }
            String base64Video = Base64.getEncoder().encodeToString(videoBytes);
            String dataUrl = "data:video/mp4;base64," + base64Video;

            log.info("视频Base64编码长度: {} 字符", base64Video.length());

            return callQwenMultimodal(prompt, "video_url", dataUrl);

        } catch (IOException e) {
            log.error("Qwen多模态模型调用失败", e);
            throw new RuntimeException("Qwen多模态模型调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用Qwen多模态API（OpenAI兼容格式）
     *
     * @param prompt   提示词
     * @param mediaType 媒体类型: "image_url" 或 "video_url"
     * @param mediaUrl  媒体数据的URL (base64 data URL 或 公开URL)
     * @return 模型返回的文本内容
     */
    private String callQwenMultimodal(String prompt, String mediaType, String mediaUrl) throws IOException {
        // 构建请求体（OpenAI兼容格式）
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);

        // 构建消息列表
        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");

        // 构建多模态内容数组
        JSONArray content = new JSONArray();

        // 媒体内容（图片或视频）
        JSONObject mediaContent = new JSONObject();
        mediaContent.put("type", mediaType);
        JSONObject mediaUrlObj = new JSONObject();
        mediaUrlObj.put("url", mediaUrl);
        if ("image_url".equals(mediaType)) {
            mediaContent.put("image_url", mediaUrlObj);
        } else {
            mediaContent.put("video_url", mediaUrlObj);
        }
        content.add(mediaContent);

        // 文本内容
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.add(textContent);

        userMessage.put("content", content);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        // 设置参数（OpenAI兼容格式：参数放在顶层）
        requestBody.put("max_tokens", 4096);

        String jsonBody = JSON.toJSONString(requestBody);
        log.debug("请求体: {}", jsonBody);

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
                log.error("Qwen API调用失败, 状态码: {}, 响应: {}", statusCode, responseBody);
                throw new RuntimeException("Qwen API调用失败, 状态码: " + statusCode + ", 响应: " + responseBody);
            }

            // 解析响应（OpenAI兼容格式）
            JSONObject responseJson = JSON.parseObject(responseBody);
            JSONArray choices = responseJson.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("Qwen API返回的choices为空: {}", responseBody);
                throw new RuntimeException("Qwen API返回的choices为空");
            }

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String contentText = message.getString("content");

            log.info("响应长度: {} 字符", contentText != null ? contentText.length() : 0);
            log.info("响应内容: {}", contentText != null && contentText.length() > 500 ? contentText.substring(0, 500) + "..." : contentText);

            return contentText;
        }
    }
}
