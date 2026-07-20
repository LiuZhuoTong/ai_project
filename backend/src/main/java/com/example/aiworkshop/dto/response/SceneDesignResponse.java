package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 场景设计响应DTO
 * 
 * <p>表示LLM返回的场景设计结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneDesignResponse {

    /**
     * 场景列表
     */
    private List<Scene> scenes;

    /**
     * 单个场景信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scene {
        
        /**
         * 场景ID
         */
        private Integer sceneId;
        
        /**
         * 场景描述
         */
        private String sceneDescription;
        
        /**
         * 视觉风格提示词
         */
        private String sceneStyle;
        
        /**
         * 场景中的句子列表
         */
        private List<String> sentences;
    }
}
