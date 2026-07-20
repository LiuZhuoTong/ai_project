package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关键帧图片提示词响应DTO
 * 
 * <p>表示LLM返回的图片提示词生成结果，包含中英文两种提示词。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyframeDesignResponse {

    /**
     * 中文提示词
     */
    private String Chinese;

    /**
     * 英文提示词
     */
    private String English;
}
