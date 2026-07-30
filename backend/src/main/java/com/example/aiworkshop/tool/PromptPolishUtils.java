package com.example.aiworkshop.tool;

import com.example.aiworkshop.service.LlmService;
import com.example.aiworkshop.service.QwenMultiModalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 提示词润色工具类
 * 
 * <p>提供提示词润色相关的工具方法，使用DeepSeek和Qwen模型对提示词进行优化。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PromptPolishUtils {

    private final LlmService llmService;
    private final QwenMultiModalService qwenMultiModalService;

    /**
     * 使用DeepSeek对提示词进行润色
     * 
     * <p>根据不同的模式（image/video/video_audio）生成适合AI模型的英文提示词。</p>
     * 
     * @param originalPrompt 原始提示词
     * @param mode 润色模式：image/video/video_audio
     * @return 润色后的提示词，失败时返回null
     */
    public String polishPromptWithDeepSeek(String originalPrompt, String mode) {
        if (originalPrompt == null || originalPrompt.isEmpty()) {
            return null;
        }

        log.debug("开始调用LlmService润色提示词，模式: {}", mode);

        try {
            String prompt = null;
            if ("video".equals(mode)) {
                prompt = String.format(
                    "请将以下文字润色成适合AI视频生成的英文提示词，使用wan2.2模型。要求：\n" +
                    "1. 保持原有的核心含义\n" +
                    "2. 添加丰富的场景描述和动态元素\n" +
                    "3. 描述镜头角度和运动方式\n" +
                    "4. 视频要具有电影质感\n" +
                    "5. 运动要平滑自然，符合物理规律\n" +
                    "6. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "7. 遮挡关系在运动过程中保持正确\n" +
                    "8. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "输入文本：\n%s",
                    originalPrompt
                );
            } else if ("video_audio".equals(mode)) {
                prompt = String.format(
                    "请将以下文字润色成适合AI视频生成的英文提示词，使用ltx-2模型生成带音频的视频。要求：\n" +
                    "1. 保持原有核心含义，但只聚焦于3-5秒内能发生的连续动作\n" +
                    "2. 删除所有音频、音乐、音效相关描述\n" +
                    "3. 镜头只能使用\"固定机位\"，禁止出现\"推拉摇移升降\"等运镜词汇\n" +
                    "4. 禁止使用\"电影感\"\"梦幻\"\"诗意\"等抽象风格词\n" +
                    "5. 用具体的动词描述动作（如：劈开、翻滚、闪烁、滑行），不要写\"符合物理规律\n" +
                    "6. 字数控制在50-80英文单词之间\n" +
                    "7. 句式结构：[固定机位] + [场景描述] + [主体][具体动词] + [物理结果] + [光影/粒子变化]\n" +
                    "输入文本：\n%s",
                    originalPrompt
                );
            } else if ("image".equals(mode)) {
                prompt = String.format(
                    "请将以下文字润色成适合AI图像生成的英文提示词，保持原有的核心含义，添加丰富的细节描述（如场景、光影、风格、色彩、构图等）：\n%s",
                    originalPrompt
                );
            }

            if (StringUtils.isEmpty(prompt)) {
                return originalPrompt;
            } else {
                String polishedText = llmService.generate(prompt);
                if (polishedText != null && !polishedText.isEmpty()) {
                    polishedText = polishedText.trim().replaceAll("\\s+", " ");
                    log.info("LlmService润色成功({}) - 原始: '{}', 润色后: '{}'", mode, originalPrompt, polishedText);
                    return polishedText;
                } else {
                    log.warn("LlmService返回空响应");
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("LlmService调用异常({}): {}", mode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 使用 Qwen3.7-plus 多模态模型分析图片并润色提示词
     * 
     * <p>调用 QwenMultiModalService 分析图片内容，并结合用户描述生成适合AI生成的提示词。</p>
     * 
     * @param originalPrompt 用户原始输入的描述
     * @param imagePath 图片文件路径
     * @param mode 生成模式："image"、"video"、"video_audio" 或 "face_consistency"
     * @return 润色后的提示词，失败时返回 null
     */
    public String polishPromptWithQwen(String originalPrompt, String imagePath, String mode) {
        if (originalPrompt == null || originalPrompt.isEmpty()) {
            return null;
        }

        log.debug("开始调用Qwen3.7-plus多模态模型润色提示词，模式: {}, 图片路径: {}", mode, imagePath);

        try {
            String analyzePrompt = null;
            if ("video".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片，并结合以下描述生成适合AI视频生成的英文提示词（使用wan2.2模型）。要求：\n" +
                    "1. 描述图片中的主要内容、场景、人物、物体\n" +
                    "2. 添加丰富的动态元素和镜头运动描述\n" +
                    "3. 视频要具有电影质感\n" +
                    "4. 运动要平滑自然，符合物理规律\n" +
                    "5. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "6. 遮挡关系在运动过程中保持正确\n" +
                    "7. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "8. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            } else if ("video_audio".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片，并结合以下描述生成适合AI视频生成的英文提示词（使用ltx-2模型生成带音频的视频）。要求：\n" +
                    "1. 描述图片中的主要内容、场景、人物、物体\n" +
                    "2. 添加丰富的动态元素和镜头运动描述\n" +
                    "3. 视频要具有电影质感\n" +
                    "4. 考虑背景音乐和音效的氛围\n" +
                    "5. 保持提示词适合音频视频同步生成\n" +
                    "6. 运动要平滑自然，符合物理规律\n" +
                    "7. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "8. 遮挡关系在运动过程中保持正确\n" +
                    "9. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "10. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            } else if ("face_consistency".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片中的人物，并结合以下描述生成适合AI人物一致性迁移的英文提示词（使用flux模型）。要求：\n" +
                    "1. 详细描述人物的面部特征（五官、表情、发型）\n" +
                    "2. 描述人物的姿态和动作\n" +
                    "3. 描述人物的着装和配饰\n" +
                    "4. 保持人物特征的一致性\n" +
                    "5. 添加场景描述和光影效果\n" +
                    "6. 保持提示词适合高质量图像生成\n" +
                    "7. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            }

            if (StringUtils.isEmpty(analyzePrompt)) {
                return originalPrompt;
            } else {
                String response = qwenMultiModalService.analyzeImage(analyzePrompt, imagePath);
                if (response != null && !response.isEmpty()) {
                    String polishedText = response.trim().replaceAll("\\s+", " ");
                    log.info("Qwen3.7-plus润色成功({}) - 原始: '{}', 润色后: '{}'", mode, originalPrompt, polishedText);
                    return polishedText;
                } else {
                    log.warn("Qwen3.7-plus返回空响应");
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Qwen3.7-plus调用异常({}): {}", mode, e.getMessage(), e);
            return null;
        }
    }
}