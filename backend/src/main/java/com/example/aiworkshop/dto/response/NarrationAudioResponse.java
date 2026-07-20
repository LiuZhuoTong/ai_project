package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语音情感设计响应DTO
 * 
 * <p>表示LLM返回的语音情感设计结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NarrationAudioResponse {

    /**
     * 语音音频列表
     */
    private List<NarrationItem> narrationAudio;

    /**
     * 根据场景ID和分镜ID查找对应的语音音频项
     * 
     * @param sceneId 场景编号
     * @param shotId 分镜编号
     * @return 匹配的语音音频项，如果未找到返回null
     */
    public NarrationItem findBySceneIdAndShotId(Integer sceneId, Integer shotId) {
        if (narrationAudio == null || narrationAudio.isEmpty()) {
            return null;
        }
        return narrationAudio.stream()
                .filter(item -> sceneId.equals(item.getSceneId()) && shotId.equals(item.getShotId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 单个语音音频项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NarrationItem {
        
        /**
         * 场景编号
         */
        private Integer sceneId;
        
        /**
         * 分镜编号
         */
        private Integer shotId;
        
        /**
         * 该分镜对应的具体台词
         */
        private String text;
        
        /**
         * 情感类型（平静/兴奋/严肃/轻松/好奇/惊讶/疑惑/肯定/愤怒）
         */
        private String emotion;
        
        /**
         * 播报员（从播报员枚举中选择）
         */
        private String speaker;
    }
}
