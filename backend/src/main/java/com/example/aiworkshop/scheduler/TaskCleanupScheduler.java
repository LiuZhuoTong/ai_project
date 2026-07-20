package com.example.aiworkshop.scheduler;

import com.example.aiworkshop.entity.Task;
import com.example.aiworkshop.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务清理定时调度器
 * 
 * <p>定时清理24小时前已完成的任务及其关联文件。</p>
 * <p>仅清理状态为"执行成功"或"执行失败"的任务，排队中和执行中的任务不会被清理。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCleanupScheduler {

    private final TaskRepository taskRepository;

    @Value("${file.upload-dir:/root/comfyui/storage-user/input}")
    private String uploadDir;

    @Value("${file.output-dir:/root/comfyui/storage-user/output}")
    private String outputDir;

    /**
     * 每小时执行一次清理任务
     * 
     * <p>使用cron表达式：每小时的第0分钟执行</p>
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupExpiredTasks() {
        log.info("定时任务开始：清理24小时前的已完成任务");
        
        try {
            int deletedCount = executeCleanup();
            log.info("定时任务完成：共清理 {} 个任务", deletedCount);
        } catch (Exception e) {
            log.error("定时清理任务失败", e);
        }
    }

    /**
     * 执行清理操作
     * 
     * @return 删除的任务数量
     */
    private int executeCleanup() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        log.info("清理截止时间: {}", cutoffTime);
        
        // 查询24小时前已完成（成功或失败）的任务
        List<Task> expiredTasks = taskRepository.findExpiredCompletedTasks(cutoffTime);
        
        if (expiredTasks.isEmpty()) {
            log.info("没有需要清理的过期任务");
            return 0;
        }
        
        log.info("找到 {} 个需要清理的过期任务", expiredTasks.size());
        
        int deletedCount = 0;
        int fileDeletedCount = 0;
        
        for (Task task : expiredTasks) {
            try {
                // 删除上传的文件
                String uploadPath = task.getFilePath();
                if (uploadPath != null && !uploadPath.isEmpty()) {
                    File uploadFile = new File(uploadPath);
                    if (uploadFile.exists()) {
                        if (uploadFile.delete()) {
                            log.info("已删除上传文件: {}", uploadPath);
                            fileDeletedCount++;
                        } else {
                            log.warn("删除上传文件失败: {}", uploadPath);
                        }
                    }
                }
                
                // 删除生成的文件
                String downloadPath = task.getDownloadPath();
                if (downloadPath != null && !downloadPath.isEmpty()) {
                    File downloadFile = new File(downloadPath);
                    if (downloadFile.exists()) {
                        if (downloadFile.delete()) {
                            log.info("已删除生成文件: {}", downloadPath);
                            fileDeletedCount++;
                        } else {
                            log.warn("删除生成文件失败: {}", downloadPath);
                        }
                    }
                }
                
                // 从数据库中删除
                taskRepository.delete(task);
                deletedCount++;
                
                log.info("已清理任务: taskId={}, status={}", task.getTaskId(), task.getStatus());
                
            } catch (Exception e) {
                log.error("清理任务失败: taskId={}", task.getTaskId(), e);
            }
        }
        
        log.info("清理完成: 删除任务 {} 个, 删除文件 {} 个", deletedCount, fileDeletedCount);
        return deletedCount;
    }

    /**
     * 手动触发清理（用于测试或手动调用）
     * 
     * @return 删除的任务数量
     */
    public int triggerCleanup() {
        return executeCleanup();
    }

    /**
     * 每小时执行一次清理服务器目录文件
     * 
     * <p>清理 upload-dir 和 output-dir 目录下24小时前创建的文件。</p>
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupServerFiles() {
        log.info("定时任务开始：清理24小时前的服务器目录文件");
        
        try {
            int deletedCount = cleanupDirectory(uploadDir);
            deletedCount += cleanupDirectory(outputDir);
            log.info("定时任务完成：共清理 {} 个文件", deletedCount);
        } catch (Exception e) {
            log.error("定时清理服务器目录文件失败", e);
        }
    }

    /**
     * 清理指定目录下24小时前创建的文件
     * 
     * @param dirPath 目录路径
     * @return 删除的文件数量
     */
    private int cleanupDirectory(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            log.warn("目录路径为空，跳过清理");
            return 0;
        }

        File dir = new File(dirPath);
        if (!dir.exists()) {
            log.warn("目录不存在: {}", dirPath);
            return 0;
        }

        if (!dir.isDirectory()) {
            log.warn("路径不是目录: {}", dirPath);
            return 0;
        }

        long cutoffTime = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
        int deletedCount = 0;

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            log.info("目录为空，无需清理: {}", dirPath);
            return 0;
        }

        log.info("扫描目录: {}, 文件数量: {}", dirPath, files.length);

        for (File file : files) {
            if (file.isFile()) {
                if (file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        log.info("已删除过期文件: {}", file.getAbsolutePath());
                        deletedCount++;
                    } else {
                        log.warn("删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            } else if (file.isDirectory()) {
                int subDirDeleted = cleanupDirectory(file.getAbsolutePath());
                deletedCount += subDirDeleted;
            }
        }

        log.info("目录清理完成: {}, 删除文件数量: {}", dirPath, deletedCount);
        return deletedCount;
    }
}