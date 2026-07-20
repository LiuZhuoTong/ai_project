package com.example.aiworkshop.tool;

import com.example.aiworkshop.dto.response.ImageQualityDetectionResponse;
import com.example.aiworkshop.dto.response.KeyframeDesignResponse;
import com.example.aiworkshop.dto.response.NarrationAudioResponse;
import com.example.aiworkshop.dto.response.SceneDesignResponse;
import com.example.aiworkshop.dto.response.StoryboardDesignResponse;
import com.example.aiworkshop.dto.response.VideoDesignResponse;
import com.example.aiworkshop.dto.response.VideoQualityDetectionResponse;
import com.example.aiworkshop.service.LlmService;
import com.example.aiworkshop.service.QwenMultiModalService;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * VideoGenerateTools 单元测试
 *
 * <p>测试场景设计、分镜设计等方法。</p>
 */
@SpringBootTest(classes = {VideoGenerateTools.class, LlmService.class, QwenMultiModalService.class})
public class VideoGenerateToolsTest {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerateToolsTest.class);

    @Autowired
    private VideoGenerateTools videoGenerateTools;

    /**
     * 测试 sceneDesign 方法：从文件读取解说词并执行场景设计
     */
    @Test
    public void testSceneDesign() throws IOException {
        log.info("========== 测试 VideoGenerateTools.sceneDesign ==========");

        // 从测试资源文件读取解说词
        String scriptPath = "src/test/resources/test_script.txt";
        String narration = new String(Files.readAllBytes(Paths.get(scriptPath)), "UTF-8");

        log.info("读取解说词完成，长度: {} 字符", narration.length());

        // 调用 sceneDesign 方法
        SceneDesignResponse response = videoGenerateTools.sceneDesign(narration);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证场景列表不为空
        Assertions.assertNotNull(response.getScenes(), "场景列表不应为空");
        Assertions.assertFalse(response.getScenes().isEmpty(), "场景列表不应为空");

        log.info("场景数量: {}", response.getScenes().size());

        // 打印场景信息
        response.getScenes().forEach(scene -> {
            log.info("场景ID: {}, 场景描述: {}", scene.getSceneId(), scene.getSceneDescription());
        });

        log.info("========== testSceneDesign 测试通过 ==========");
    }

    /**
     * 测试 storyboardDesign 方法：从文件读取解说词和场景设计结果，执行分镜设计
     */
    @Test
    public void testStoryboardDesign() throws IOException {
        log.info("========== 测试 VideoGenerateTools.storyboardDesign ==========");

        // 从测试资源文件读取解说词
        String scriptPath = "src/test/resources/test_script.txt";
        String narration = new String(Files.readAllBytes(Paths.get(scriptPath)), "UTF-8");
        log.info("读取解说词完成，长度: {} 字符", narration.length());

        // 从测试资源文件读取场景设计JSON并转换为SceneDesignResponse对象
        String sceneJsonPath = "src/test/resources/scene_design_test.txt";
        String sceneJson = new String(Files.readAllBytes(Paths.get(sceneJsonPath)), "UTF-8");
        SceneDesignResponse sceneResponse = JSON.parseObject(sceneJson, SceneDesignResponse.class);
        log.info("读取场景设计完成，场景数: {}", sceneResponse.getScenes().size());

        // 调用 storyboardDesign 方法
        StoryboardDesignResponse response = videoGenerateTools.storyboardDesign(narration, sceneResponse);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证分镜列表不为空
        Assertions.assertNotNull(response.getStoryboard(), "分镜列表不应为空");
        Assertions.assertFalse(response.getStoryboard().isEmpty(), "分镜列表不应为空");

        log.info("分镜数量: {}", response.getStoryboard().size());

        // 打印分镜信息（遍历StoryboardScene）
        response.getStoryboard().forEach(storyboardScene -> {
            log.info("场景ID: {}", storyboardScene.getSceneId());
            if (storyboardScene.getShots() != null) {
                storyboardScene.getShots().forEach(shot -> {
                    log.info("  镜头ID: {}, 视觉描述: {}", shot.getShotId(), shot.getVisualDescription());
                });
            }
        });

        log.info("========== testStoryboardDesign 测试通过 ==========");
    }

    /**
     * 测试 narrationAudioDesign 方法：从文件读取解说词和分镜设计结果，执行语音情感设计
     */
    @Test
    public void testNarrationAudioDesign() throws IOException {
        log.info("========== 测试 VideoGenerateTools.narrationAudioDesign ==========");

        // 从测试资源文件读取解说词
        String scriptPath = "src/test/resources/test_script.txt";
        String narration = new String(Files.readAllBytes(Paths.get(scriptPath)), "UTF-8");
        log.info("读取解说词完成，长度: {} 字符", narration.length());

        // 从测试资源文件读取分镜设计JSON并转换为StoryboardDesignResponse对象
        String storyboardJsonPath = "src/test/resources/storyboard_design_test.txt";
        String storyboardJson = new String(Files.readAllBytes(Paths.get(storyboardJsonPath)), "UTF-8");
        StoryboardDesignResponse storyboardResponse = JSON.parseObject(storyboardJson, StoryboardDesignResponse.class);
        log.info("读取分镜设计完成，分镜数: {}", storyboardResponse.getStoryboard().size());

        // 调用 narrationAudioDesign 方法
        NarrationAudioResponse response = videoGenerateTools.narrationAudioDesign(narration, storyboardResponse);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证语音情感列表不为空
        Assertions.assertNotNull(response.getNarrationAudio(), "语音情感列表不应为空");
        Assertions.assertFalse(response.getNarrationAudio().isEmpty(), "语音情感列表不应为空");

        log.info("语音情感设计数量: {}", response.getNarrationAudio().size());

        // 打印语音情感信息
        response.getNarrationAudio().forEach(item -> {
            log.info("场景ID: {}, 分镜ID: {}, 情感: {}, 播报员: {}",
                    item.getSceneId(), item.getShotId(), item.getEmotion(), item.getSpeaker());
        });

        log.info("========== testNarrationAudioDesign 测试通过 ==========");
    }

    /**
     * 测试 keyframeDesign 方法：从文件读取解说词和分镜设计结果，执行关键帧设计
     */
    @Test
    public void testKeyframeDesign() throws IOException {
        log.info("========== 测试 VideoGenerateTools.keyframeDesign ==========");

        // 从测试资源文件读取解说词
        String scriptPath = "src/test/resources/test_script.txt";
        String narration = new String(Files.readAllBytes(Paths.get(scriptPath)), "UTF-8");
        log.info("读取解说词完成，长度: {} 字符", narration.length());

        // 从测试资源文件读取分镜设计JSON并转换为StoryboardDesignResponse对象
        String storyboardJsonPath = "src/test/resources/storyboard_design_test.txt";
        String storyboardJson = new String(Files.readAllBytes(Paths.get(storyboardJsonPath)), "UTF-8");
        StoryboardDesignResponse storyboardResponse = JSON.parseObject(storyboardJson, StoryboardDesignResponse.class);
        log.info("读取分镜设计完成，分镜数: {}", storyboardResponse.getStoryboard().size());

        // 取第一个场景的第一个镜头进行测试
        StoryboardDesignResponse.StoryboardScene firstScene = storyboardResponse.getStoryboard().get(0);
        Integer sceneId = firstScene.getSceneId();
        StoryboardDesignResponse.Shot firstShot = firstScene.getShots().get(0);
        log.info("测试场景ID: {}, 测试镜头ID: {}", sceneId, firstShot.getShotId());

        // 调用 keyframeDesign 方法
        KeyframeDesignResponse response = videoGenerateTools.keyframeDesign(narration, sceneId, firstShot);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证中英文提示词不为空
        Assertions.assertNotNull(response.getChinese(), "中文提示词不应为空");
        Assertions.assertNotNull(response.getEnglish(), "英文提示词不应为空");

        // 验证提示词包含 sceneId_shotId 前缀
        String expectedPrefix = sceneId + "_" + firstShot.getShotId();
        Assertions.assertTrue(response.getChinese().startsWith(expectedPrefix), 
                "中文提示词应包含前缀 " + expectedPrefix);
        Assertions.assertTrue(response.getEnglish().startsWith(expectedPrefix), 
                "英文提示词应包含前缀 " + expectedPrefix);

        log.info("中文提示词:\n{}", response.getChinese());
        log.info("英文提示词:\n{}", response.getEnglish());

        log.info("========== testKeyframeDesign 测试通过 ==========");
    }

    /**
     * 测试 imageQualityDetection 方法：从文件读取关键帧设计结果和测试图片，执行图片质量检测
     */
    @Test
    public void testImageQualityDetection() throws IOException {
        log.info("========== 测试 VideoGenerateTools.imageQualityDetection ==========");

        // 从测试资源文件读取关键帧设计JSON并转换为KeyframeDesignResponse对象
        String keyframeJsonPath = "src/test/resources/keyFrame_design_test.txt";
        String keyframeJson = new String(Files.readAllBytes(Paths.get(keyframeJsonPath)), "UTF-8");
        KeyframeDesignResponse keyframeResponse = JSON.parseObject(keyframeJson, KeyframeDesignResponse.class);
        log.info("读取关键帧设计完成");
        log.info("中文提示词:\n{}", keyframeResponse.getChinese());
        log.info("英文提示词:\n{}", keyframeResponse.getEnglish());

        // 测试图片路径
        String imagePath = "src/test/resources/aircraft_carrier.jpg";
        log.info("测试图片路径: {}", imagePath);

        // 调用 imageQualityDetection 方法
        ImageQualityDetectionResponse response = videoGenerateTools.imageQualityDetection(keyframeResponse, imagePath);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证总分不为空且在有效范围内
        Assertions.assertNotNull(response.getTotalScore(), "总分不应为空");
        Assertions.assertTrue(response.getTotalScore() >= 0 && response.getTotalScore() <= 10, 
                "总分应在0-10范围内");

        // 验证是否通过检测不为空
        Assertions.assertNotNull(response.getPass(), "是否通过检测不应为空");

        log.info("图片质量检测结果:");
        log.info("总分: {}, 是否通过: {}", response.getTotalScore(), response.getPass());

        // 打印各维度评分
        if (response.getScores() != null) {
            response.getScores().forEach((key, scoreDetail) -> {
                log.info("  {}: 分数={}, 理由={}", key, scoreDetail.getScore(), scoreDetail.getReason());
            });
        }

        // 打印问题列表
        if (response.getIssues() != null && !response.getIssues().isEmpty()) {
            log.info("问题列表:");
            response.getIssues().forEach(issue -> {
                log.info("  - {}", issue);
            });
        }

        // 打印改进建议
        if (response.getImprovementSuggestions() != null) {
            log.info("改进建议中文提示词:\n{}", response.getImprovementSuggestions().getChinese());
            log.info("改进建议英文提示词:\n{}", response.getImprovementSuggestions().getEnglish());
        }

        log.info("========== testImageQualityDetection 测试通过 ==========");
    }

    /**
     * 测试 videoDesign 方法：从文件读取关键帧设计结果、分镜设计结果和测试图片，执行视频提示词生成
     */
    @Test
    public void testVideoDesign() throws IOException {
        log.info("========== 测试 VideoGenerateTools.videoDesign ==========");

        // 从测试资源文件读取关键帧设计JSON并转换为KeyframeDesignResponse对象
        String keyframeJsonPath = "src/test/resources/keyFrame_design_test.txt";
        String keyframeJson = new String(Files.readAllBytes(Paths.get(keyframeJsonPath)), "UTF-8");
        KeyframeDesignResponse keyframeResponse = JSON.parseObject(keyframeJson, KeyframeDesignResponse.class);
        log.info("读取关键帧设计完成");
        log.info("关键帧中文提示词:\n{}", keyframeResponse.getChinese());
        log.info("关键帧英文提示词:\n{}", keyframeResponse.getEnglish());

        // 从测试资源文件读取分镜设计JSON并转换为StoryboardDesignResponse对象
        String storyboardJsonPath = "src/test/resources/storyboard_design_test.txt";
        String storyboardJson = new String(Files.readAllBytes(Paths.get(storyboardJsonPath)), "UTF-8");
        StoryboardDesignResponse storyboardResponse = JSON.parseObject(storyboardJson, StoryboardDesignResponse.class);
        log.info("读取分镜设计完成，分镜数: {}", storyboardResponse.getStoryboard().size());

        // 取场景1镜头1进行测试
        StoryboardDesignResponse.StoryboardScene scene1 = storyboardResponse.getStoryboard().get(0);
        Integer sceneId = scene1.getSceneId();
        StoryboardDesignResponse.Shot shot1 = scene1.getShots().get(0);
        log.info("测试场景ID: {}, 测试镜头ID: {}", sceneId, shot1.getShotId());
        log.info("镜头类型: {}, 运镜方式: {}, 预估时长: {}秒", 
                shot1.getShotType(), shot1.getCameraMovement(), shot1.getEstimatedDuration());

        // 测试图片路径
        String imagePath = "src/test/resources/aircraft_carrier.jpg";
        log.info("测试图片路径: {}", imagePath);

        // 调用 videoDesign 方法
        VideoDesignResponse response = videoGenerateTools.videoDesign(keyframeResponse, sceneId, shot1, imagePath);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证中英文提示词不为空
        Assertions.assertNotNull(response.getChinese(), "中文视频提示词不应为空");
        Assertions.assertNotNull(response.getEnglish(), "英文视频提示词不应为空");

        log.info("视频提示词生成结果:");
        log.info("中文视频提示词:\n{}", response.getChinese());
        log.info("英文视频提示词:\n{}", response.getEnglish());
        
        if (response.getEstimatedDuration() != null) {
            log.info("预估视频时长: {} 秒", response.getEstimatedDuration());
        }

        log.info("========== testVideoDesign 测试通过 ==========");
    }

    /**
     * 测试 videoQualityDetection 方法：从文件读取视频设计结果和测试视频，执行视频质量检测
     */
    @Test
    public void testVideoQualityDetection() throws IOException {
        log.info("========== 测试 VideoGenerateTools.videoQualityDetection ==========");

        // 从测试资源文件读取视频设计JSON并转换为VideoDesignResponse对象
        String videoDesignJsonPath = "src/test/resources/video_design_test.txt";
        String videoDesignJson = new String(Files.readAllBytes(Paths.get(videoDesignJsonPath)), "UTF-8");
        VideoDesignResponse videoDesignResponse = JSON.parseObject(videoDesignJson, VideoDesignResponse.class);
        log.info("读取视频设计完成");
        log.info("视频中文提示词:\n{}", videoDesignResponse.getChinese());
        log.info("视频英文提示词:\n{}", videoDesignResponse.getEnglish());

        // 测试视频路径
        String videoPath = "src/test/resources/aircraft_carrier_video.mp4";
        log.info("测试视频路径: {}", videoPath);

        // 调用 videoQualityDetection 方法
        VideoQualityDetectionResponse response = videoGenerateTools.videoQualityDetection(videoDesignResponse, videoPath);

        // 验证返回结果不为空
        Assertions.assertNotNull(response, "响应不应为空");

        // 验证总分不为空且在有效范围内
        Assertions.assertNotNull(response.getTotalScore(), "总分不应为空");
        Assertions.assertTrue(response.getTotalScore() >= 0 && response.getTotalScore() <= 10, 
                "总分应在0-10范围内");

        // 验证是否通过检测不为空
        Assertions.assertNotNull(response.getPass(), "是否通过检测不应为空");

        log.info("视频质量检测结果:");
        log.info("总分: {}, 是否通过: {}", response.getTotalScore(), response.getPass());

        // 打印各维度评分
        if (response.getScores() != null) {
            response.getScores().forEach((key, scoreDetail) -> {
                log.info("  {}: 分数={}, 理由={}", key, scoreDetail.getScore(), scoreDetail.getReason());
            });
        }

        // 打印问题列表
        if (response.getIssues() != null && !response.getIssues().isEmpty()) {
            log.info("问题列表:");
            response.getIssues().forEach(issue -> {
                log.info("  - {}", issue);
            });
        }

        // 打印改进建议
        if (response.getImprovementSuggestions() != null) {
            log.info("改进建议中文提示词:\n{}", response.getImprovementSuggestions().getChinese());
            log.info("改进建议英文提示词:\n{}", response.getImprovementSuggestions().getEnglish());
        }

        log.info("========== testVideoQualityDetection 测试通过 ==========");
    }
}
