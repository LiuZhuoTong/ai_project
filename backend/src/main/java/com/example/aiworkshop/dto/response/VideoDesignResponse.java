package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频提示词设计响应DTO
 * 
 * <p>表示Qwen多模态模型返回的视频提示词生成结果，包含中英文两种提示词。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoDesignResponse {

    /**
     * 中文视频提示词
     */
    private String Chinese;

    /**
     * 英文视频提示词
     */
    private String English;

    /**
     * 预估视频时长（秒）
     */
    private Integer estimatedDuration;
}
