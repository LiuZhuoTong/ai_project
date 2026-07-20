package com.example.aiworkshop.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * QwenMultiModalService 单元测试
 *
 * <p>测试 {@link QwenMultiModalService#analyzeImage(String, String)} 和
 * {@link QwenMultiModalService#analyzeVideo(String, String)} 方法的正确性。</p>
 */
@SpringBootTest(classes = QwenMultiModalService.class)
public class QwenMultiModalServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QwenMultiModalServiceTest.class);

    @Autowired
    private QwenMultiModalService qwenMultiModalService;

    /**
     * 测试 analyzeImage 方法：分析测试图片
     */
    @Test
    public void testAnalyzeImage() {
        log.info("========== 测试 QwenMultiModalService.analyzeImage ==========");

        // 测试图片路径（classpath: 根目录）
        String imagePath = "src/test/resources/test_image.png";

        // 测试提示词
        String prompt = "请详细描述这张图片中的内容，包括场景、人物、物体、颜色、风格等。";

        // 调用 analyzeImage 方法
        String response = qwenMultiModalService.analyzeImage(prompt, imagePath);

        log.info("响应内容:\n{}", response);
        log.info("响应长度: {} 字符", response != null ? response.length() : 0);

        // 验证结果
        Assertions.assertNotNull(response, "响应不应为空");
        Assertions.assertFalse(response.isEmpty(), "响应不应为空字符串");
        Assertions.assertTrue(response.length() > 20, "响应内容太短，可能有问题");

        log.info("========== testAnalyzeImage 测试通过 ==========");
    }

    /**
     * 测试 analyzeVideo 方法：分析测试视频
     */
    @Test
    public void testAnalyzeVideo() {
        log.info("========== 测试 QwenMultiModalService.analyzeVideo ==========");

        // 测试视频路径
        String videoPath = "src/test/resources/test_video.mp4";

        // 测试提示词
        String prompt = "请详细描述这段视频中的内容，包括场景、人物动作、物体、画面风格等。";

        // 调用 analyzeVideo 方法
        String response = qwenMultiModalService.analyzeVideo(prompt, videoPath);

        log.info("响应内容:\n{}", response);
        log.info("响应长度: {} 字符", response != null ? response.length() : 0);

        // 验证结果
        Assertions.assertNotNull(response, "响应不应为空");
        Assertions.assertFalse(response.isEmpty(), "响应不应为空字符串");
        Assertions.assertTrue(response.length() > 20, "响应内容太短，可能有问题");

        log.info("========== testAnalyzeVideo 测试通过 ==========");
    }
}
