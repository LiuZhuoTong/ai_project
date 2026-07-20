
package com.example.aiworkshop.controller;

import com.example.aiworkshop.dto.response.PageResponse;
import com.example.aiworkshop.dto.response.TaskResponse;
import com.example.aiworkshop.entity.TaskType;
import com.example.aiworkshop.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.aiworkshop.repository.TaskRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Slf4j
public class TaskController {

    private final TaskService taskService;
    private final TaskRepository taskRepository;

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitTask(
            @RequestParam String userId,
            @RequestParam String type,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String emotion,
            @RequestParam(required = false) Integer videoDuration,
            @RequestParam(required = false, defaultValue = "true") Boolean isPolish,
            @RequestParam(required = false) String fatherTaskId) {
        
        try {
            String enumName = type.toUpperCase().replace("-", "_");
            TaskType taskType = TaskType.valueOf(enumName);
            String taskId = taskService.submitTask(userId, taskType, description, file, speaker, emotion, videoDuration, isPolish, fatherTaskId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("taskId", taskId);
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.error("无效的任务类型: {}", type, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "无效的任务类型: " + type);
            return ResponseEntity.badRequest().body(result);
        } catch (IOException e) {
            log.error("任务提交失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "文件上传失败");
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("任务提交失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable String taskId) {
        TaskResponse task = taskService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    /**
     * 获取用户任务列表（不分页，兼容旧接口）
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TaskResponse>> getTasksByUser(@PathVariable String userId) {
        List<TaskResponse> tasks = taskService.getTasksByUser(userId);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 分页获取用户任务列表
     * 
     * @param userId 用户ID
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @param status 状态筛选（可选，默认"all"）
     * @param sort 排序方式（可选，默认"newest"）
     * @param keyword 搜索关键词（可选）
     * @return 分页响应
     */
    @GetMapping("/user/{userId}/paged")
    public ResponseEntity<PageResponse<TaskResponse>> getTasksByUserPaged(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false) String keyword) {
        
        log.info("分页查询用户任务: userId={}, page={}, size={}, status={}, sort={}, keyword={}", 
                 userId, page, size, status, sort, keyword);
        
        PageResponse<TaskResponse> response = taskService.getTasksByUserPaged(userId, page, size, status, sort, keyword);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/submit/science-video")
    public ResponseEntity<Map<String, Object>> submitScienceVideoTask(
            @RequestParam String userId,
            @RequestParam String narration) {
        
        log.info("提交科普视频生成任务: userId={}, narrationLength={}", userId, narration != null ? narration.length() : 0);
        
        try {
            String shortDescription = narration;
            if (shortDescription != null && shortDescription.length() > 15) {
                shortDescription = shortDescription.substring(0, 15);
            }

            String taskId = taskService.submitTask(userId, TaskType.SCIENCE_VIDEO, shortDescription, null, null, null, null, false, null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("taskId", taskId);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("科普视频生成任务提交失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/{taskId}/download")
    public ResponseEntity<Resource> downloadTask(@PathVariable String taskId) {
        log.info("下载任务文件: taskId={}", taskId);
        
        try {
            // 从数据库获取任务
            com.example.aiworkshop.entity.Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("任务不存在: taskId={}", taskId);
                return ResponseEntity.notFound().build();
            }
            
            String downloadPath = task.getDownloadPath();
            if (downloadPath == null || downloadPath.isEmpty()) {
                log.warn("任务无下载文件: taskId={}", taskId);
                return ResponseEntity.notFound().build();
            }
            
            // 检查文件是否存在
            File file = new File(downloadPath);
            if (!file.exists()) {
                log.warn("文件不存在: {}", downloadPath);
                return ResponseEntity.notFound().build();
            }
            
            // 返回文件资源
            Resource resource = new FileSystemResource(file);
            String filename = file.getName();
            
            log.info("开始下载文件: taskId={}, filename={}", taskId, filename);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("下载文件失败: taskId={}", taskId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
