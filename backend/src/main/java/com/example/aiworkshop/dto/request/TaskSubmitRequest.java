
/**
 * 任务提交请求DTO
 * 
 * <p>用于接收前端提交任务时的请求参数。</p>
 */
package com.example.aiworkshop.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务提交请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSubmitRequest {

    /**
     * 任务类型
     * 可选值：text-to-image, text-to-video, image-to-video等
     */
    private String type;

    /**
     * 用户输入的描述文字
     */
    private String description;
}
