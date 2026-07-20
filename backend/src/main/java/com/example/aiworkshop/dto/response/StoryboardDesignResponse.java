package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分镜设计响应DTO
 * 
 * <p>表示LLM返回的分镜设计结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryboardDesignResponse {

    /**
     * 分镜列表
     */
    private List<StoryboardScene> storyboard;

    /**
     * 单个场景的分镜信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoryboardScene {
        
        /**
         * 场景ID
         */
        private Integer sceneId;
        
        /**
         * 镜头列表
         */
        private List<Shot> shots;
    }

    /**
     * 单个镜头信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Shot {
        
        /**
         * 镜头ID
         */
        private Integer shotId;
        
        /**
         * 镜头类型（远景、全景、中景、近景、特写）
         */
        private String shotType;
        
        /**
         * 运镜方式（固定、推、拉、摇、移、跟）
         */
        private String cameraMovement;
        
        /**
         * 画面视觉描述的详细提示词
         */
        private String visualDescription;
        
        /**
         * 该镜头对应的句子列表
         */
        private List<String> sentences;
        
        /**
         * 预估时长（秒）
         */
        private Integer estimatedDuration;
    }
}
