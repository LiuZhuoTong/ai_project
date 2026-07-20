package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 视频质量检测响应DTO
 * 
 * <p>表示Qwen多模态模型返回的视频质量检测结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoQualityDetectionResponse {

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
    private Map<String, VideoScoreDetail> scores;

    /**
     * 问题列表
     */
    private List<String> issues;

    /**
     * 带时间戳的问题列表
     */
    private List<TimestampIssue> timestampIssues;

    /**
     * 改进建议提示词
     */
    private VideoImprovementSuggestions improvementSuggestions;

    /**
     * 单个维度的评分详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoScoreDetail {
        
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
     * 带时间戳的问题
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimestampIssue {
        
        /**
         * 时间戳（格式：HH:MM:SS）
         */
        private String time;
        
        /**
         * 问题描述
         */
        private String issue;
    }

    /**
     * 改进建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoImprovementSuggestions {
        
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
