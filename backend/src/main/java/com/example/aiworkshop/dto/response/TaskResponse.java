
/**
 * 任务响应DTO
 * 
 * <p>用于向前端返回任务信息。</p>
 */
package com.example.aiworkshop.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.example.aiworkshop.entity.TaskType;

import java.time.LocalDateTime;

/**
 * 任务响应对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {

    /**
     * 任务唯一标识
     */
    private String taskId;

    /**
     * ComfyUI返回的任务ID
     */
    private String promptId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 任务进度（0-100）
     */
    private Integer progress;

    /**
     * 生成文件下载路径
     */
    private String downloadPath;

    /**
     * 任务类型
     */
    private TaskType type;

    /**
     * 用户输入的描述文字
     */
    private String description;

    /**
     * 父任务ID
     */
    private String fatherTaskId;

    /**
     * 任务提交时间
     */
    private LocalDateTime submitTime;

    /**
     * 任务完成时间
     */
    private LocalDateTime completeTime;
}
