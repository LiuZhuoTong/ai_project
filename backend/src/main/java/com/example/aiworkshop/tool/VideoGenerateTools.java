package com.example.aiworkshop.tool;

import com.example.aiworkshop.dto.response.SceneDesignResponse;
import com.example.aiworkshop.dto.response.StoryboardDesignResponse;
import com.example.aiworkshop.dto.response.NarrationAudioResponse;
import com.example.aiworkshop.dto.response.KeyframeDesignResponse;
import com.example.aiworkshop.dto.response.ImageQualityDetectionResponse;
import com.example.aiworkshop.dto.response.VideoDesignResponse;
import com.example.aiworkshop.dto.response.VideoQualityDetectionResponse;
import com.example.aiworkshop.service.LlmService;
import com.example.aiworkshop.service.QwenMultiModalService;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 视频生成工具类
 * 
 * <p>提供视频生成相关的各类工具方法，包括场景设计、分镜设计、音频生成、视频处理等功能。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VideoGenerateTools {

    private final LlmService llmService;
    private final QwenMultiModalService qwenMultiModalService;
    private final PromptPolishUtils promptPolishUtils;

    @Value("${prompt.template-dir:original_prompt_template}")
    private String templateDir;

    /**
     * 从字符串中提取最外层的JSON数据（{...} 或 [...]）
     * 
     * <p>大模型返回的数据可能包含Markdown标记、额外文字说明等，此方法能稳健地提取出JSON部分。</p>
     * 
     * @param response 大模型返回的原始响应
     * @return 提取出的JSON字符串，如果未找到则返回原始响应
     */
    /**
     * 从大模型响应中提取JSON数据
     * <p>智能提取最外层JSON结构，处理以下情况：</p>
     * <ul>
     *   <li>Markdown代码块包裹（如 ```json ... ```）</li>
     *   <li>前后缀文本说明</li>
     *   <li>字符串内部包含花括号/方括号</li>
     *   <li>转义字符处理</li>
     *   <li>大模型返回错误信息的情况</li>
     * </ul>
     * 
     * @param response 大模型的原始响应
     * @return 提取的JSON字符串，如果无法提取则返回原始响应
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        
        response = response.trim();
        
        // 去除Markdown代码块标记
        if (response.startsWith("```")) {
            int endIndex = response.lastIndexOf("```");
            if (endIndex > 0) {
                response = response.substring(3, endIndex).trim();
                // 如果是 ```json 格式，去除 json 标记
                if (response.startsWith("json")) {
                    response = response.substring(4).trim();
                }
            }
        }
        
        // 查找JSON起始位置
        int braceStart = response.indexOf('{');
        int bracketStart = response.indexOf('[');
        
        int jsonStart = -1;
        char startChar = '\0';
        char endChar = '\0';
        
        if (braceStart != -1 && (bracketStart == -1 || braceStart < bracketStart)) {
            jsonStart = braceStart;
            startChar = '{';
            endChar = '}';
        } else if (bracketStart != -1) {
            jsonStart = bracketStart;
            startChar = '[';
            endChar = ']';
        }
        
        if (jsonStart == -1) {
            log.warn("未找到JSON起始标记，返回原始响应");
            return response;
        }
        
        // 正确计算JSON结束位置，处理字符串内部的花括号/方括号
        int depth = 0;
        int jsonEnd = -1;
        boolean inString = false;
        boolean escaped = false;
        
        for (int i = jsonStart; i < response.length(); i++) {
            char c = response.charAt(i);
            
            // 处理转义字符
            if (escaped) {
                escaped = false;
                continue;
            }
            
            // 处理字符串边界
            if (c == '\\') {
                escaped = true;
                continue;
            }
            
            if (c == '"') {
                inString = !inString;
                continue;
            }
            
            // 只有不在字符串内部时才计数花括号/方括号
            if (!inString) {
                if (c == startChar) {
                    depth++;
                } else if (c == endChar) {
                    depth--;
                    if (depth == 0) {
                        jsonEnd = i + 1;
                        break;
                    }
                }
            }
        }
        
        if (jsonEnd == -1) {
            log.warn("未找到JSON结束标记，返回原始响应");
            return response;
        }
        
        String extracted = response.substring(jsonStart, jsonEnd);
        log.debug("提取JSON成功，原始长度: {}, 提取后长度: {}", response.length(), extracted.length());
        
        // 验证JSON是否有效
        try {
            JSON.parse(extracted);
            return extracted;
        } catch (Exception e) {
            log.warn("提取的JSON无效，尝试修复。错误: {}", e.getMessage());
            // 如果提取的JSON无效，尝试简单修复：去除尾部多余字符
            return fixJson(extracted);
        }
    }
    
    /**
     * 简单修复无效JSON
     * <p>尝试修复常见的JSON格式问题：</p>
     * <ul>
     *   <li>去除尾部多余的逗号</li>
     *   <li>去除尾部多余的字符</li>
     * </ul>
     * 
     * @param json 待修复的JSON字符串
     * @return 修复后的JSON字符串
     */
    private String fixJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        
        // 去除尾部多余的逗号
        json = json.replaceAll(",\\s*([}\\]])", "$1");
        
        // 再次验证
        try {
            JSON.parse(json);
            return json;
        } catch (Exception e) {
            log.warn("JSON修复失败，返回原始JSON");
            return json;
        }
    }

    private String readTemplate(String fileName) throws IOException {
        String path = templateDir + "/" + fileName;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("模板文件不存在: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), "UTF-8"))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    /**
     * 解说词润色
     *
     * <p>调用DeepSeek对原始解说词进行润色改写，使其更适合制作科普视频。</p>
     * <p>润色后的解说词内容更精彩、有干货，且每句话简短精炼，确保单个镜头解说时长不超过5秒。</p>
     *
     * @param narration 原始解说词
     * @return 润色后的解说词，失败时返回原始解说词
     */
    public String polishNarration(String narration) {
        log.info("执行解说词润色，原始解说词长度: {} 字符", narration != null ? narration.length() : 0);

        if (narration == null || narration.trim().isEmpty()) {
            return narration;
        }

        try {
            String promptTemplate = readTemplate("0_narration_polish.txt");

            String prompt = promptTemplate.replace("{original_text}", narration);

            // 调用DeepSeek生成润色后的解说词
            String polishedNarration = llmService.generate(prompt);

            if (polishedNarration != null && !polishedNarration.trim().isEmpty()) {
                polishedNarration = polishedNarration.trim();
                log.info("解说词润色完成，润色后长度: {} 字符", polishedNarration.length());
                log.info("原始解说词:\n{}", narration);
                log.info("润色后解说词:\n{}", polishedNarration);
                return polishedNarration;
            } else {
                log.warn("解说词润色返回空响应，使用原始解说词");
                return narration;
            }

        } catch (Exception e) {
            log.error("解说词润色失败，使用原始解说词: {}", e.getMessage(), e);
            return narration;
        }
    }

    /**
     * 场景设计
     *
     * <p>根据解说词内容设计视频场景，调用DeepSeek生成场景设计结果。</p>
     *
     * @param narration 解说词内容
     * @return 场景设计结果
     */
    public SceneDesignResponse sceneDesign(String narration) {
        log.info("执行场景设计，解说词内容长度: {} 字符", narration != null ? narration.length() : 0);

        try {
            String promptTemplate = readTemplate("1_scene_design.txt");
            
            String prompt = promptTemplate.replace("{original_text}", narration);
            
            // 调用LLM生成响应
            String jsonResponse = llmService.generateJson(prompt);
            
            log.info("场景设计完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("场景设计结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, SceneDesignResponse.class);
            
        } catch (Exception e) {
            log.error("场景设计失败：发生错误", e);
            throw new RuntimeException("场景设计失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分镜设计
     * 
     * <p>根据场景设计结果生成分镜脚本，调用DeepSeek生成分镜设计结果。</p>
     * 
     * @param sceneResponse 场景设计结果
     * @return 分镜设计结果
     */
    public StoryboardDesignResponse storyboardDesign(SceneDesignResponse sceneResponse) {
        log.info("执行分镜设计，场景数: {}", 
                sceneResponse != null && sceneResponse.getScenes() != null ? sceneResponse.getScenes().size() : 0);

        try {
            String sceneJson = JSON.toJSONString(sceneResponse);
            
            String promptTemplate = readTemplate("2_storyboard_design.txt");
            
            String prompt = promptTemplate.replace("{scene_json}", sceneJson);
            
            // 调用LLM生成响应
            String jsonResponse = llmService.generateJson(prompt);
            
            log.info("分镜设计完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("分镜设计结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, StoryboardDesignResponse.class);
            
        } catch (Exception e) {
            log.error("分镜设计失败：发生错误", e);
            throw new RuntimeException("分镜设计失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解说音频生成
     * 
     * <p>根据分镜设计结果生成语音情感设计，调用DeepSeek生成音频描述。</p>
     * 
     * @param storyboardResponse 分镜设计结果
     * @return 语音情感设计结果
     */
    public NarrationAudioResponse narrationAudioDesign(StoryboardDesignResponse storyboardResponse) {
        log.info("执行解说音频生成，分镜场景数: {}", 
                storyboardResponse != null && storyboardResponse.getStoryboard() != null ? storyboardResponse.getStoryboard().size() : 0);

        try {
            String storyboardJson = JSON.toJSONString(storyboardResponse);
            
            String promptTemplate = readTemplate("3_speech_emotion_design.txt");
            
            String prompt = promptTemplate.replace("{storyboard_json}", storyboardJson);
            
            // 调用LLM生成响应
            String jsonResponse = llmService.generateJson(prompt);
            
            log.info("解说音频生成完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("解说音频设计结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, NarrationAudioResponse.class);
            
        } catch (Exception e) {
            log.error("解说音频生成失败：发生错误", e);
            throw new RuntimeException("解说音频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关键帧图片提示词生成
     * 
     * <p>根据镜头信息生成图片提示词，调用DeepSeek生成中英文提示词。</p>
     * 
     * @param sceneId 场景编号
     * @param shot 镜头信息
     * @return 图片提示词结果
     */
    public KeyframeDesignResponse keyframeDesign(Integer sceneId, StoryboardDesignResponse.Shot shot) {
        log.info("执行关键帧图片提示词生成，场景ID: {}, 镜头ID: {}", 
                sceneId,
                shot != null ? shot.getShotId() : null);

        try {
            String shotJson = JSON.toJSONString(shot);
            
            String promptTemplate = readTemplate("4_image_design.txt");
            
            String prompt = promptTemplate.replace("{single_shot_json}", shotJson);
            
            // 调用LLM生成响应
            String jsonResponse = llmService.generateJson(prompt);
            
            log.info("关键帧图片提示词生成完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("关键帧设计结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            KeyframeDesignResponse response = JSON.parseObject(cleanJson, KeyframeDesignResponse.class);
            
            // 添加sceneId_shotId前缀
            String prefix = sceneId + "_" + shot.getShotId();
            if (response.getChinese() != null) {
                response.setChinese(prefix + "_" + response.getChinese());
            }
            if (response.getEnglish() != null) {
                response.setEnglish(prefix + "_" + response.getEnglish());
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("关键帧图片提示词生成失败：发生错误", e);
            throw new RuntimeException("关键帧图片提示词生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 图片质量检测
     * 
     * <p>调用Qwen多模态模型检测生成的图片质量。</p>
     * 
     * @param keyframeResponse 图片生成提示词
     * @param imagePath 图片文件路径
     * @return 质量检测结果
     */
    public ImageQualityDetectionResponse imageQualityDetection(KeyframeDesignResponse keyframeResponse, String imagePath) {
        log.info("执行图片质量检测，图片路径: {}", imagePath);

        try {
            String imageChinesePrompt = keyframeResponse.getChinese();
            String imageEnglishPrompt = keyframeResponse.getEnglish();
            
            String promptTemplate = readTemplate("5_image_quality.txt");
            
            String prompt = promptTemplate.replace("{image_chinese_prompt}", imageChinesePrompt)
                                         .replace("{image_english_prompt}", imageEnglishPrompt);
            
            // 调用Qwen多模态模型分析图片
            String jsonResponse = qwenMultiModalService.analyzeImage(prompt, imagePath);
            
            log.info("图片质量检测完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("图片质量检测结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, ImageQualityDetectionResponse.class);
            
        } catch (Exception e) {
            log.error("图片质量检测失败：发生错误", e);
            throw new RuntimeException("图片质量检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 视频提示词生成
     * 
     * <p>调用DeepSeek模型根据关键帧提示词和分镜信息生成图生视频提示词。</p>
     *
     * @param sceneId 场景编号
     * @param shot 镜头信息
     * @param keyframeDesign 关键帧提示词（来自关键帧设计响应）
     * @param customDuration 自定义视频时长
     * @return 视频提示词结果
     */
    public VideoDesignResponse videoDesign(Integer sceneId, StoryboardDesignResponse.Shot shot, String keyframeDesign, Integer customDuration) {
        int duration = customDuration != null ? customDuration : 5;
        log.info("执行视频提示词生成，场景ID: {}, 时长: {} 秒", sceneId, duration);

        try {
            String visualDescription = shot.getVisualDescription();
            
            String promptTemplate = readTemplate("6_video_design.txt");
            
            String prompt = promptTemplate.replace("{keyframe_design}", keyframeDesign != null ? keyframeDesign : "")
                                         .replace("{visual_description}", visualDescription != null ? visualDescription : "")
                                         .replace("{duration}", String.valueOf(duration));
            
            // 调用DeepSeek模型生成视频提示词
            String jsonResponse = llmService.generate(prompt);
            
            log.info("视频提示词生成完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            
            // 日志输出时添加sceneId_shotId前缀
            String prefix = sceneId + "_" + shot.getShotId();
            log.info("视频设计结果[{}]:\n{}", prefix, jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, VideoDesignResponse.class);
            
        } catch (Exception e) {
            log.error("视频提示词生成失败：发生错误", e);
            throw new RuntimeException("视频提示词生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 文字生视频提示词生成
     * 
     * <p>调用DeepSeek模型根据分镜信息生成用于文生视频的提示词。</p>
     * 
     * @param sceneId 场景ID
     * @param shot 分镜信息
     * @param videoDuration 视频时长（秒）
     * @return 视频设计响应
     */
    public VideoDesignResponse videoDesignByText(Integer sceneId, StoryboardDesignResponse.Shot shot, Integer videoDuration) {
        int duration = videoDuration != null ? videoDuration : 5;
        log.info("执行文字生视频提示词生成，场景ID: {}, 时长: {} 秒", sceneId, duration);

        try {
            String visualDescription = shot.getVisualDescription();
            
            String promptTemplate = readTemplate("8_video_design_by_text.txt");
            
            String prompt = promptTemplate.replace("{visual_description}", visualDescription)
                                         .replace("{duration}", String.valueOf(duration));
            
            // 调用DeepSeek模型生成视频提示词
            String jsonResponse = llmService.generate(prompt);
            
            log.info("文字生视频提示词生成完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            
            // 日志输出时添加sceneId_shotId前缀
            String prefix = sceneId + "_" + shot.getShotId();
            log.info("文字生视频设计结果[{}]:\n{}", prefix, jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, VideoDesignResponse.class);
            
        } catch (Exception e) {
            log.error("文字生视频提示词生成失败：发生错误", e);
            throw new RuntimeException("文字生视频提示词生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 视频质量检测
     * 
     * <p>调用Qwen多模态模型检测生成的视频质量。</p>
     * 
     * @param videoDesignResponse 视频生成提示词
     * @param videoPath 视频文件路径
     * @return 质量检测结果
     */
    public VideoQualityDetectionResponse videoQualityDetection(VideoDesignResponse videoDesignResponse, String videoPath) {
        log.info("执行视频质量检测，视频路径: {}", videoPath);

        try {
            String videoChinesePrompt = videoDesignResponse.getChinese();
            String videoEnglishPrompt = videoDesignResponse.getEnglish();
            
            String promptTemplate = readTemplate("7_video_quality.txt");
            
            String prompt = promptTemplate.replace("{video_chinese_prompt}", videoChinesePrompt)
                                         .replace("{video_english_prompt}", videoEnglishPrompt);
            
            // 调用Qwen多模态模型分析视频
            String jsonResponse = qwenMultiModalService.analyzeVideo(prompt, videoPath);
            
            log.info("视频质量检测完成，响应长度: {} 字符", jsonResponse != null ? jsonResponse.length() : 0);
            log.info("视频质量检测结果:\n{}", jsonResponse);
            
            // 提取JSON数据并解析
            String cleanJson = extractJson(jsonResponse);
            return JSON.parseObject(cleanJson, VideoQualityDetectionResponse.class);
            
        } catch (Exception e) {
            log.error("视频质量检测失败：发生错误", e);
            throw new RuntimeException("视频质量检测失败: " + e.getMessage(), e);
        }
    }
}