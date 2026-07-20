package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 图片质量检测响应DTO
 * 
 * <p>表示Qwen多模态模型返回的图片质量检测结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageQualityDetectionResponse {

    /**
     * 总分（0-10）
     */
    private Integer totalScore;

    /**
     * 是否通过检测
     */
    private Boolean pass;

    /**
     * 各维度评分
     */
    private Map<String, ScoreDetail> scores;

    /**
     * 问题列表
     */
    private List<String> issues;

    /**
     * 改进建议提示词
     */
    private ImprovementSuggestions improvementSuggestions;

    /**
     * 单个维度的评分详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreDetail {
        
        /**
         * 分数（0-10）
         */
        private Integer score;
        
        /**
         * 评分理由
         */
        private String reason;
    }

    /**
     * 改进建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImprovementSuggestions {
        
        /**
         * 修改后的中文提示词
         */
        private String Chinese;
        
        /**
         * 修改后的英文提示词
         */
        private String English;
    }
}
