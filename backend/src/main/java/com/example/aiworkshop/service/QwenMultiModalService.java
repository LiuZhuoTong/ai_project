package com.example.aiworkshop.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
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

    @Value("${qwen.timeout.connect:30000}")
    private int connectTimeout;

    @Value("${qwen.timeout.read:120000}")
    private int readTimeout;

    @Value("${qwen.timeout.connection-request:30000}")
    private int connectionRequestTimeout;

    @Value("${qwen.retry.max:3}")
    private int maxRetry;

    @Value("${qwen.retry.delay:2000}")
    private long retryDelay;

    // Qwen-Image-2.0-Pro文生图配置
    @Value("${qwen.image.model:qwen-image-2.0-pro}")
    private String imageModel;

    @Value("${qwen.image.size:1024x1024}")
    private String imageSize;

    @Value("${qwen.image.output-dir:/root/comfyui/storage-user/output/image}")
    private String imageOutputDir;

    @Value("${qwen.image.file-prefix:qwen_image_}")
    private String imageFilePrefix;

    private String chatEndpoint;

    private CloseableHttpClient httpClient;

    @PostConstruct
    public void init() {
        log.info("========== QwenMultiModalService 初始化 ==========");
        log.info("API Key: {}", apiKey != null && !apiKey.isEmpty() ? "已配置" : "未配置");
        log.info("Base URL: {}", baseUrl);
        log.info("Model: {}", model);
        log.info("连接超时: {}ms, 读取超时: {}ms", connectTimeout, readTimeout);
        log.info("最大重试次数: {}, 重试间隔: {}ms", maxRetry, retryDelay);

        // 拼接聊天补全接口地址
        String url = baseUrl;
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "chat/completions";
        this.chatEndpoint = url;

        // 配置HttpClient，设置超时时间和连接池
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(readTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .build();

        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .build();
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
     * 调用Qwen-Image-2.0-Pro生成图片
     *
     * @param prompt 图片生成提示词
     * @param size   图片尺寸，如 "1024x1024"
     * @return 生成的图片文件路径
     */
    public String generateImage(String prompt, String size) {
        log.info("========== 调用Qwen-Image-2.0-Pro生成图片 ==========");
        log.info("提示词长度: {} 字符", prompt != null ? prompt.length() : 0);
        log.info("图片尺寸: {}", size != null ? size : imageSize);

        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", imageModel);

            JSONObject input = new JSONObject();
            
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            
            JSONArray content = new JSONArray();
            JSONObject textContent = new JSONObject();
            textContent.put("text", prompt);
            content.add(textContent);
            
            message.put("content", content);
            messages.add(message);
            
            input.put("messages", messages);
            requestBody.put("input", input);

            JSONObject parameters = new JSONObject();
            parameters.put("n", 1);
            parameters.put("size", (size != null ? size : imageSize).replace("x", "*"));
            requestBody.put("parameters", parameters);

            String jsonBody = JSON.toJSONString(requestBody);
            log.debug("请求体长度: {} 字符", jsonBody.length());

            HttpPost httpPost = new HttpPost(apiUrl);
            httpPost.setHeader("Content-Type", "application/json; charset=UTF-8");
            httpPost.setHeader("Authorization", "Bearer " + apiKey);
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));

            IOException lastException = null;
            for (int attempt = 1; attempt <= maxRetry; attempt++) {
                try {
                    log.info("第 {}/{} 次调用Qwen-Image-2.0-Pro API", attempt, maxRetry);

                    try (CloseableHttpClient freshClient = HttpClientBuilder.create()
                            .setDefaultRequestConfig(
                                    RequestConfig.custom()
                                            .setConnectTimeout(connectTimeout)
                                            .setSocketTimeout(readTimeout)
                                            .setConnectionRequestTimeout(connectionRequestTimeout)
                                            .build())
                            .build();
                         CloseableHttpResponse httpResponse = freshClient.execute(httpPost)) {

                        int statusCode = httpResponse.getStatusLine().getStatusCode();
                        String responseBody = EntityUtils.toString(httpResponse.getEntity(), StandardCharsets.UTF_8);

                        if (statusCode != 200) {
                            log.error("Qwen-Image API调用失败, 状态码: {}, 响应: {}", statusCode, responseBody);
                            throw new RuntimeException("Qwen-Image API调用失败, 状态码: " + statusCode + ", 响应: " + responseBody);
                        }

                        JSONObject responseJson = JSON.parseObject(responseBody);
                        log.info("Qwen-Image API返回完整响应: {}", responseBody);
                        
                        JSONObject output = responseJson.getJSONObject("output");
                        if (output == null) {
                            log.error("Qwen-Image API返回的output为空: {}", responseBody);
                            throw new RuntimeException("Qwen-Image API返回的output为空");
                        }

                        JSONArray choices = output.getJSONArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            log.error("Qwen-Image API返回的choices为空: {}", responseBody);
                            throw new RuntimeException("Qwen-Image API返回的choices为空");
                        }

                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject responseMessage = choice.getJSONObject("message");
                        if (responseMessage == null) {
                            log.error("Qwen-Image API返回的message为空: {}", responseBody);
                            throw new RuntimeException("Qwen-Image API返回的message为空");
                        }

                        JSONArray responseContent = responseMessage.getJSONArray("content");
                        if (responseContent == null || responseContent.isEmpty()) {
                            log.error("Qwen-Image API返回的content为空: {}", responseBody);
                            throw new RuntimeException("Qwen-Image API返回的content为空");
                        }

                        JSONObject contentItem = responseContent.getJSONObject(0);
                        String imageUrl = contentItem.getString("image");
                        if (imageUrl == null || imageUrl.isEmpty()) {
                            log.error("Qwen-Image API返回的图片URL为空: {}", responseBody);
                            throw new RuntimeException("Qwen-Image API返回的图片URL为空");
                        }

                        imageUrl = imageUrl.trim().replaceAll("^`+|`+$", "");
                        log.info("图片生成成功，图片URL: {}", imageUrl);

                        String imagePath = downloadImage(imageUrl);
                        log.info("图片下载完成，本地路径: {}", imagePath);

                        return imagePath;
                    }
                } catch (IOException e) {
                    lastException = e;
                    log.error("第 {}/{} 次调用Qwen-Image-2.0-Pro API失败: {}", attempt, maxRetry, e.getMessage());

                    if (attempt < maxRetry) {
                        try {
                            log.info("等待 {}ms 后重试...", retryDelay);
                            Thread.sleep(retryDelay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("重试等待被中断", ie);
                        }
                    }
                }
            }

            log.error("Qwen-Image-2.0-Pro调用失败，已重试 {} 次", maxRetry);
            throw lastException != null ? lastException : new IOException("Qwen-Image-2.0-Pro调用失败");

        } catch (IOException e) {
            log.error("Qwen-Image-2.0-Pro调用失败", e);
            throw new RuntimeException("Qwen-Image-2.0-Pro调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载图片到本地
     *
     * @param imageUrl 图片URL
     * @return 本地文件路径
     */
    private String downloadImage(String imageUrl) throws IOException {
        File dir = new File(imageOutputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String fileName = imageFilePrefix + System.currentTimeMillis() + ".png";
        String filePath = imageOutputDir + "/" + fileName;

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(new HttpGet(imageUrl));
             FileOutputStream fos = new FileOutputStream(filePath)) {

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IOException("图片下载失败，状态码: " + statusCode);
            }

            InputStream inputStream = response.getEntity().getContent();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        return filePath;
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
        log.debug("请求体长度: {} 字符", jsonBody.length());

        // 创建HTTP POST请求
        HttpPost httpPost = new HttpPost(chatEndpoint);
        httpPost.setHeader("Content-Type", "application/json; charset=UTF-8");
        httpPost.setHeader("Authorization", "Bearer " + apiKey);
        httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));

        // 添加重试机制
        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                log.info("第 {}/{} 次调用Qwen多模态API", attempt, maxRetry);
                
                // 每次重试都创建新的连接，避免复用已断开的连接
                try (CloseableHttpClient freshClient = HttpClientBuilder.create()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setConnectTimeout(connectTimeout)
                                        .setSocketTimeout(readTimeout)
                                        .setConnectionRequestTimeout(connectionRequestTimeout)
                                        .build())
                        .build();
                     CloseableHttpResponse httpResponse = freshClient.execute(httpPost)) {
                    
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
            } catch (IOException e) {
                lastException = e;
                log.error("第 {}/{} 次调用Qwen多模态API失败: {}", attempt, maxRetry, e.getMessage());
                
                // 如果不是最后一次尝试，等待后重试
                if (attempt < maxRetry) {
                    try {
                        log.info("等待 {}ms 后重试...", retryDelay);
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("重试等待被中断", ie);
                    }
                }
            }
        }

        // 所有重试都失败
        log.error("Qwen多模态模型调用失败，已重试 {} 次", maxRetry);
        throw lastException != null ? lastException : new IOException("Qwen多模态模型调用失败");
    }
}
