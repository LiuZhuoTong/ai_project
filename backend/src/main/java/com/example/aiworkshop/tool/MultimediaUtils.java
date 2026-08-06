package com.example.aiworkshop.tool;

import com.example.aiworkshop.dto.response.KeyframeDesignResponse;
import com.example.aiworkshop.dto.response.NarrationAudioResponse;
import com.example.aiworkshop.dto.response.VideoDesignResponse;
import com.example.aiworkshop.entity.Task;
import com.example.aiworkshop.entity.TaskType;
import com.example.aiworkshop.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class MultimediaUtils {

    private static final Logger log = LoggerFactory.getLogger(MultimediaUtils.class);

    private final TaskService taskService;

    private static final int MAX_COMFYUI_RETRY = 10;

    private static final Object COMFYUI_RESTART_LOCK = new Object();

    /**
     * 重启ComfyUI服务
     *
     * <p>执行 docker restart comfyui 命令重启ComfyUI容器，并等待60秒使其完全启动。</p>
     * <p>使用synchronized锁防止多个线程同时触发重启操作。</p>
     *
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 获取重启锁，确保同时只有一个线程执行重启
     * 2. 执行 docker restart comfyui 命令
     * 3. 验证命令执行结果
     * 4. 等待60秒让ComfyUI完全启动
     * 5. 释放锁
     * </pre>
     */
    private void restartComfyUI() {
        synchronized (COMFYUI_RESTART_LOCK) {
            log.warn("========== 开始重启ComfyUI服务 ==========");
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                        "docker", "restart", "comfyui"
                );
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
                if (!completed) {
                    log.error("重启ComfyUI超时");
                    process.destroyForcibly();
                    throw new RuntimeException("重启ComfyUI超时");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.error("重启ComfyUI失败，退出码: {}, 输出: {}", exitCode, output);
                    throw new RuntimeException("重启ComfyUI失败: " + output);
                }

                log.info("ComfyUI重启成功: {}", output);

                log.info("等待ComfyUI完全启动（60秒）...");
                Thread.sleep(60000);
                log.info("ComfyUI启动等待完成，可以继续提交任务");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("重启ComfyUI被中断");
                throw new RuntimeException("重启ComfyUI被中断", e);
            } catch (java.io.IOException e) {
                log.error("执行docker restart命令失败", e);
                throw new RuntimeException("执行docker restart命令失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * ComfyUI任务重试包装器
     *
     * <p>封装ComfyUI任务的重试逻辑：最多重试10次，如果全部失败则重启ComfyUI并再尝试一次。</p>
     *
     * <h3>重试策略：</h3>
     * <pre>
     * 1. 最多重试10次，每次间隔60秒
     * 2. 10次全部失败后，执行docker restart comfyui重启服务
     * 3. 重启后等待60秒让服务完全启动
     * 4. 重启后再尝试1次
     * 5. 如果重启后仍然失败，抛出异常
     * </pre>
     *
     * @param taskSupplier 任务执行器，封装提交ComfyUI任务并等待结果的完整逻辑
     * @param taskName 任务名称，用于日志记录
     * @return 任务执行成功后的结果路径
     * @throws RuntimeException 重试耗尽且重启后仍失败时抛出
     */
    private String executeWithComfyUIRetry(java.util.function.Supplier<String> taskSupplier, String taskName) {
        String lastErrorMsg = "未知错误";

        for (int attempt = 1; attempt <= MAX_COMFYUI_RETRY; attempt++) {
            try {
                log.info("{}第{}次尝试", taskName, attempt);
                return taskSupplier.get();
            } catch (Exception e) {
                lastErrorMsg = e.getMessage();
                log.error("{}第{}次尝试失败: {}", taskName, attempt, e.getMessage());

                if (attempt < MAX_COMFYUI_RETRY) {
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                }
            }
        }

        log.error("{}连续{}次失败，重启ComfyUI服务...", taskName, MAX_COMFYUI_RETRY);
        restartComfyUI();

        log.info("{}重启后重试", taskName);
        try {
            return taskSupplier.get();
        } catch (Exception e) {
            log.error("{}重启后重试失败: {}", taskName, e.getMessage());
            throw new RuntimeException(taskName + "失败，已重试" + MAX_COMFYUI_RETRY + "次且重启后仍失败: " + e.getMessage());
        }
    }

    /**
     * 安全休眠，将InterruptedException转为RuntimeException
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程休眠被中断", e);
        }
    }

    /**
     * 安全创建FileMultipartFile，将IOException转为RuntimeException
     */
    private static FileMultipartFile createMultipartFile(File file) {
        try {
            return new FileMultipartFile(file);
        } catch (java.io.IOException e) {
            throw new RuntimeException("创建MultipartFile失败: " + e.getMessage(), e);
        }
    }

    /**
     * 安全提交任务，将IOException转为RuntimeException
     */
    private String submitTaskSafely(String userId, TaskType type, String description, 
                                    org.springframework.web.multipart.MultipartFile file,
                                    String speaker, String emotion, Integer videoDuration, 
                                    Boolean isPolish, String fatherTaskId) {
        try {
            return taskService.submitTask(userId, type, description, file, speaker, emotion, 
                    videoDuration, isPolish, fatherTaskId);
        } catch (java.io.IOException e) {
            throw new RuntimeException("提交任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取音频文件时长（秒）
     *
     * <p>使用ffprobe获取音频文件的时长，单位为秒。</p>
     *
     * @param audioPath 音频文件路径
     * @return 音频时长（秒），如果获取失败返回null
     */
    public static Integer getAudioDuration(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) {
            log.error("音频路径为空");
            return null;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                "ffprobe", "-v", "quiet", "-print_format", "json", "-show_entries",
                "format=duration", audioPath
            );
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ffprobe执行失败，退出码: {}, 音频路径: {}", exitCode, audioPath);
                return null;
            }

            String jsonOutput = output.toString();
            int durationIndex = jsonOutput.indexOf("\"duration\"");
            if (durationIndex == -1) {
                log.warn("未找到音频时长信息");
                return null;
            }

            int colonIndex = jsonOutput.indexOf(":", durationIndex);
            int commaIndex = jsonOutput.indexOf(",", colonIndex);
            int endBraceIndex = jsonOutput.indexOf("}", colonIndex);
            int endIndex = Math.min(commaIndex == -1 ? endBraceIndex : commaIndex, endBraceIndex);

            String durationStr = jsonOutput.substring(colonIndex + 1, endIndex).trim()
                .replace("\"", "").replace(",", "");

            double duration = Double.parseDouble(durationStr);
            return (int) Math.ceil(duration);

        } catch (Exception e) {
            log.error("获取音频时长失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 带音频视频生成
     *
     * <p>根据视频提示词生成带音频的视频，调用ComfyUI工作流进行图生视频。</p>
     * <p>由于wan2.2模型生成的视频类型为webm，生成成功后会自动调用convertToMp4转换为MP4格式。</p>
     *
     * @param videoDesignResponse 视频设计响应，使用English字段作为提示词
     * @param imagePath 图片路径
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该视频生成任务属于哪个解说视频生成任务
     * @return 生成的视频文件路径（MP4格式）
     */
    public String generateVideoWithAudioByImage(VideoDesignResponse videoDesignResponse, String imagePath, String userId, String fatherTaskId) {
        log.info("开始生成带音频视频，视频提示词: {}", videoDesignResponse != null ? videoDesignResponse.getEnglish() : null);
        log.info("用户ID: {}, 父任务ID: {}, 预估时长: {}秒", userId, fatherTaskId, videoDesignResponse != null ? videoDesignResponse.getEstimatedDuration() : null);

        if (videoDesignResponse == null || videoDesignResponse.getEnglish() == null || videoDesignResponse.getEnglish().isEmpty()) {
            log.error("视频提示词为空");
            throw new IllegalArgumentException("视频提示词不能为空");
        }

        if (imagePath == null || imagePath.isEmpty()) {
            log.error("图片路径为空");
            throw new IllegalArgumentException("图片路径不能为空");
        }

        String description = videoDesignResponse.getEnglish();
        Integer duration = videoDesignResponse.getEstimatedDuration();

        return executeWithComfyUIRetry(() -> {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                throw new RuntimeException("图片文件不存在: " + imagePath);
            }

            FileMultipartFile multipartFile = createMultipartFile(imageFile);

            String taskId = submitTaskSafely(
                    userId,
                    TaskType.IMAGE_TO_VIDEO_AUDIO,
                    description,
                    multipartFile,
                    null,
                    null,
                    duration,
                    false,
                    fatherTaskId
            );
            log.info("带音频视频生成任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("带音频视频生成成功，文件路径: {}", downloadPath);

                    if (downloadPath != null) {
                        String mp4Path = convertToMp4(downloadPath);
                        log.info("已将视频转换为MP4格式: {}", mp4Path);
                        return mp4Path;
                    }

                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("带音频视频生成任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("带音频视频生成任务超时");
        }, "带音频视频生成");
    }

    /**
     * 文字生成带音频视频
     *
     * <p>根据视频设计响应生成带音频的视频，调用ComfyUI工作流进行文生视频（带音频）。</p>
     * <p>由于wan2.2模型生成的视频类型为webm，生成成功后会自动调用convertToMp4转换为MP4格式。</p>
     *
     * @param videoDesignResponse 视频设计响应，使用English字段作为提示词
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该视频生成任务属于哪个解说视频生成任务
     * @return 生成的视频文件路径（MP4格式）
     */
    public String generateVideoWithAudioByText(VideoDesignResponse videoDesignResponse, String userId, String fatherTaskId) {
        log.info("开始文字生成带音频视频，视频提示词: {}", videoDesignResponse != null ? videoDesignResponse.getEnglish() : null);
        log.info("用户ID: {}, 父任务ID: {}, 预估时长: {}秒", userId, fatherTaskId, videoDesignResponse != null ? videoDesignResponse.getEstimatedDuration() : null);

        if (videoDesignResponse == null || videoDesignResponse.getEnglish() == null || videoDesignResponse.getEnglish().isEmpty()) {
            log.error("视频提示词为空");
            throw new IllegalArgumentException("视频提示词不能为空");
        }

        String description = videoDesignResponse.getEnglish();
        Integer duration = videoDesignResponse.getEstimatedDuration();

        return executeWithComfyUIRetry(() -> {
            String taskId = submitTaskSafely(
                    userId,
                    TaskType.TEXT_TO_VIDEO_AUDIO,
                    description,
                    null,
                    null,
                    null,
                    duration,
                    false,
                    fatherTaskId
            );
            log.info("文字生成带音频视频任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("文字生成带音频视频成功，文件路径: {}", downloadPath);

                    if (downloadPath != null) {
                        String mp4Path = convertToMp4(downloadPath);
                        log.info("已将视频转换为MP4格式: {}", mp4Path);
                        return mp4Path;
                    }

                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("文字生成带音频视频任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("文字生成带音频视频任务超时");
        }, "文字生成带音频视频");
    }

    /**
     * 图片生成视频
     *
     * <p>根据视频提示词生成不带音频的视频，调用ComfyUI工作流进行图生视频。</p>
     * <p>由于wan2.2模型生成的视频类型为webm，生成成功后会自动调用convertToMp4转换为MP4格式。</p>
     *
     * @param videoDesignResponse 视频设计响应，使用English字段作为提示词
     * @param imagePath 图片路径
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该视频生成任务属于哪个解说视频生成任务
     * @return 生成的视频文件路径（MP4格式）
     */
    public String generateVideoByImage(VideoDesignResponse videoDesignResponse, String imagePath, String userId, String fatherTaskId) {
        log.info("开始生成视频，视频提示词: {}", videoDesignResponse != null ? videoDesignResponse.getEnglish() : null);
        log.info("用户ID: {}, 父任务ID: {}, 预估时长: {}秒", userId, fatherTaskId, videoDesignResponse != null ? videoDesignResponse.getEstimatedDuration() : null);

        if (videoDesignResponse == null || videoDesignResponse.getEnglish() == null || videoDesignResponse.getEnglish().isEmpty()) {
            log.error("视频提示词为空");
            throw new IllegalArgumentException("视频提示词不能为空");
        }

        if (imagePath == null || imagePath.isEmpty()) {
            log.error("图片路径为空");
            throw new IllegalArgumentException("图片路径不能为空");
        }

        String description = videoDesignResponse.getEnglish();
        Integer duration = videoDesignResponse.getEstimatedDuration();

        return executeWithComfyUIRetry(() -> {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                throw new RuntimeException("图片文件不存在: " + imagePath);
            }

            FileMultipartFile multipartFile = createMultipartFile(imageFile);

            String taskId = submitTaskSafely(
                    userId,
                    TaskType.IMAGE_TO_VIDEO,
                    description,
                    multipartFile,
                    null,
                    null,
                    duration,
                    false,
                    fatherTaskId
            );
            log.info("视频生成任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("视频生成成功，文件路径: {}", downloadPath);

                    if (downloadPath != null) {
                        String mp4Path = convertToMp4(downloadPath);
                        log.info("已将视频转换为MP4格式: {}", mp4Path);
                        return mp4Path;
                    }

                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("视频生成任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("视频生成任务超时");
        }, "视频生成");
    }

    /**
     * 文字生成视频
     *
     * <p>根据视频设计响应生成不带音频的视频，调用ComfyUI工作流进行文生视频。</p>
     * <p>由于wan2.2模型生成的视频类型为webm，生成成功后会自动调用convertToMp4转换为MP4格式。</p>
     *
     * @param videoDesignResponse 视频设计响应，使用English字段作为提示词
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该视频生成任务属于哪个解说视频生成任务
     * @return 生成的视频文件路径（MP4格式）
     */
    public String generateVideoByText(VideoDesignResponse videoDesignResponse, String userId, String fatherTaskId) {
        log.info("开始文字生成视频，视频提示词: {}", videoDesignResponse != null ? videoDesignResponse.getEnglish() : null);
        log.info("用户ID: {}, 父任务ID: {}, 预估时长: {}秒", userId, fatherTaskId, videoDesignResponse != null ? videoDesignResponse.getEstimatedDuration() : null);

        if (videoDesignResponse == null || videoDesignResponse.getEnglish() == null || videoDesignResponse.getEnglish().isEmpty()) {
            log.error("视频提示词为空");
            throw new IllegalArgumentException("视频提示词不能为空");
        }

        String description = videoDesignResponse.getEnglish();
        Integer duration = videoDesignResponse.getEstimatedDuration();

        return executeWithComfyUIRetry(() -> {
            String taskId = submitTaskSafely(
                    userId,
                    TaskType.TEXT_TO_VIDEO,
                    description,
                    null,
                    null,
                    null,
                    duration,
                    false,
                    fatherTaskId
            );
            log.info("文字生成视频任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("文字生成视频成功，文件路径: {}", downloadPath);

                    if (downloadPath != null) {
                        String mp4Path = convertToMp4(downloadPath);
                        log.info("已将视频转换为MP4格式: {}", mp4Path);
                        return mp4Path;
                    }

                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("文字生成视频任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("文字生成视频任务超时");
        }, "文字生成视频");
    }

    /**
     * 图片生成
     *
     * <p>根据关键帧提示词生成图片，调用ComfyUI工作流进行文生图。</p>
     *
     * @param keyframeDesignResponse 关键帧提示词，使用English字段作为提示词
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该图片生成任务属于哪个解说视频生成任务
     * @return 生成的图片文件路径
     */
    public String generateImage(KeyframeDesignResponse keyframeDesignResponse, String userId, String fatherTaskId) {
        log.info("开始生成图片，关键帧提示词: {}", keyframeDesignResponse.getEnglish());
        log.info("用户ID: {}, 父任务ID: {}", userId, fatherTaskId);

        if (keyframeDesignResponse == null || keyframeDesignResponse.getEnglish() == null || keyframeDesignResponse.getEnglish().isEmpty()) {
            log.error("关键帧提示词为空");
            throw new IllegalArgumentException("关键帧提示词不能为空");
        }

        String description = keyframeDesignResponse.getEnglish();

        return executeWithComfyUIRetry(() -> {
            String taskId = submitTaskSafely(
                    userId,
                    TaskType.TEXT_TO_IMAGE,
                    description,
                    null,
                    null,
                    null,
                    null,
                    false,
                    fatherTaskId
            );
            log.info("图片生成任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("图片生成成功，文件路径: {}", downloadPath);
                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("图片生成任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("图片生成任务超时");
        }, "图片生成");
    }

    /**
     * 文字生成语音
     *
     * <p>根据旁白内容生成语音，调用ComfyUI工作流进行文生语音。</p>
     * <p>语音生成成功后，会自动调用adjustFlacSpeed调整音频语速（默认1.3倍速）。</p>
     *
     * @param narrationItem 旁白项，包含text、emotion、speaker字段
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该语音生成任务属于哪个解说视频生成任务
     * @return 语速调整后的语音文件路径
     */
    public String generateSpeech(NarrationAudioResponse.NarrationItem narrationItem, String userId, String fatherTaskId) {
        log.info("开始生成语音，台词: {}", narrationItem != null ? narrationItem.getText() : null);
        log.info("用户ID: {}, 父任务ID: {}, 播音员: {}, 情绪: {}", userId, fatherTaskId, 
                narrationItem != null ? narrationItem.getSpeaker() : null, 
                narrationItem != null ? narrationItem.getEmotion() : null);

        if (narrationItem == null || narrationItem.getText() == null || narrationItem.getText().isEmpty()) {
            log.error("台词内容为空");
            throw new IllegalArgumentException("台词内容不能为空");
        }

        String description = narrationItem.getText();
        String speaker = narrationItem.getSpeaker();
        String emotion = narrationItem.getEmotion();

        String downloadPath = executeWithComfyUIRetry(() -> {
            String taskId = submitTaskSafely(
                    userId,
                    TaskType.TEXT_TO_SPEECH,
                    description,
                    null,
                    speaker,
                    emotion,
                    null,
                    false,
                    fatherTaskId
            );
            log.info("语音生成任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    sleepQuietly(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String path = task.getDownloadPath();
                    log.info("语音生成成功，文件路径: {}", path);
                    return path;
                }

                if ("执行失败".equals(status)) {
                    throw new RuntimeException("语音生成任务失败");
                }

                sleepQuietly(3000);
            }

            throw new RuntimeException("语音生成任务超时");
        }, "语音生成");

        String audioPath;
        if (downloadPath != null && downloadPath.contains("ComfyUI_temp_")) {
            audioPath = copyToSafeLocation(downloadPath);
            log.info("已将临时音频文件复制到安全位置: {}", audioPath);
        } else {
            audioPath = downloadPath;
        }

        String adjustedPath = adjustFlacSpeed(audioPath);
        log.info("音频语速调整完成，输出路径: {}", adjustedPath);
        return adjustedPath;
    }

    /**
     * 视频音频去除
     *
     * <p>去除视频中的所有音频（包括背景音和人声）。</p>
     * ffmpeg -i input.mp4 -an -c:v copy -y output_no_audio.mp4
     * @param videoPath 视频文件路径
     * @return 处理后的视频文件路径
     */
    public static String removeAudio(String videoPath) {
        log.info("执行视频音频去除，视频路径: {}", videoPath);

        if (videoPath == null || videoPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        File inputFile = new File(videoPath);
        if (!inputFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
        }

        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            log.warn("视频文件格式不是 MP4，可能影响处理: {}", videoPath);
        }

        String outputPath = generateOutputPath(videoPath, "_no_audio");

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-an",
                    "-c:v", "copy",
                    "-y",
                    outputPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("FFmpeg处理超时，强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg处理超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("FFmpeg执行失败，退出码: {}, 错误信息: {}", exitCode, output);
                throw new RuntimeException("FFmpeg执行失败: " + output);
            }

            log.info("视频音频去除完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        }
    }

    private static boolean isFfmpegAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ffmpeg", "-version");
            Process process = processBuilder.start();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("FFmpeg 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 音视频整合（默认以较短流为准截断）
     *
     * @param videoPath MP4视频文件路径
     * @param audioPath FLAC音频文件路径
     * @return 合并后的视频文件路径
     */
    public static String mergeAudioVideo(String videoPath, String audioPath) {
        return mergeAudioVideo(videoPath, audioPath, true);
    }

    /**
     * 音视频整合
     *
     * <p>将FLAC音频文件烧录到MP4视频中。</p>
     * <p>useShortest为true时使用-shortest以较短流为准（音频结束即截断视频）；</p>
     * <p>useShortest为false时以视频长度为准，音频结束后视频继续（静音），用于结尾延时场景。</p>
     *
     * @param videoPath MP4视频文件路径
     * @param audioPath FLAC音频文件路径
     * @param useShortest 是否以较短流为准截断
     * @return 合并后的视频文件路径
     */
    public static String mergeAudioVideo(String videoPath, String audioPath, boolean useShortest) {
        log.info("执行音视频整合，视频路径: {}, 音频路径: {}", videoPath, audioPath);

        if (videoPath == null || videoPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        if (audioPath == null || audioPath.isEmpty()) {
            log.error("音频路径为空");
            throw new IllegalArgumentException("音频路径不能为空");
        }

        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
        }

        File audioFile = new File(audioPath);
        if (!audioFile.exists()) {
            log.error("音频文件不存在: {}", audioPath);
            throw new RuntimeException("音频文件不存在: " + audioPath);
        }

        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            log.warn("视频文件格式不是 MP4，可能影响处理: {}", videoPath);
        }

        if (!audioPath.toLowerCase().endsWith(".flac")) {
            log.warn("音频文件格式不是 FLAC，可能影响处理: {}", audioPath);
        }

        String outputPath = generateOutputPath(videoPath, "_with_audio");

        try {
            java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "ffmpeg",
                    "-i", videoPath,
                    "-i", audioPath,
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k"
            ));
            if (useShortest) {
                command.add("-shortest");
            }
            command.add("-y");
            command.add(outputPath);

            ProcessBuilder processBuilder = new ProcessBuilder(command);

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("FFmpeg处理超时，强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg处理超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("FFmpeg执行失败，退出码: {}, 输出信息: {}", exitCode, output);
                throw new RuntimeException("FFmpeg执行失败: " + output);
            }

            log.info("音视频整合完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        }
    }

    /**
     * 视频拼接
     *
     * <p>将多个视频片段拼接成一个完整的视频。</p>
     * 使用FFmpeg的concat demuxer方式，先生成列表文件再执行拼接。
     * 如果流复制失败（编码参数不一致），则回退到重新编码方式。
     *
     * @param videoPaths 视频片段路径列表
     * @return 拼接后的视频文件路径
     */
    public static String concatVideos(String[] videoPaths) {
        log.info("执行视频拼接，视频片段数量: {}", videoPaths != null ? videoPaths.length : 0);

        if (videoPaths == null || videoPaths.length == 0) {
            log.error("视频片段列表为空");
            throw new IllegalArgumentException("视频片段列表不能为空");
        }

        if (videoPaths.length == 1) {
            log.info("只有一个视频片段，无需拼接，直接返回");
            return videoPaths[0];
        }

        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        for (int i = 0; i < videoPaths.length; i++) {
            String path = videoPaths[i];
            if (path == null || path.isEmpty()) {
                log.error("第{}个视频片段路径为空", i + 1);
                throw new IllegalArgumentException("视频片段路径不能为空");
            }
            File file = new File(path);
            if (!file.exists()) {
                log.error("第{}个视频片段不存在: {}", i + 1, path);
                throw new RuntimeException("视频片段不存在: " + path);
            }
            if (!path.toLowerCase().endsWith(".mp4")) {
                log.warn("第{}个视频片段格式不是 MP4，可能影响拼接: {}", i + 1, path);
            }
        }

        String firstVideoPath = videoPaths[0];
        String outputPath = generateOutputPath(firstVideoPath, "_concat");

        File listFile = null;
        try {
            listFile = File.createTempFile("ffmpeg_concat_", ".txt");
            log.info("创建临时列表文件: {}", listFile.getAbsolutePath());

            try (java.io.PrintWriter writer = new java.io.PrintWriter(listFile, StandardCharsets.UTF_8.name())) {
                for (String videoPath : videoPaths) {
                    String escapedPath = videoPath.replace("\\", "\\\\");
                    writer.println("file '" + escapedPath + "'");
                }
            }
            log.info("临时列表文件写入完成，共{}个视频片段", videoPaths.length);

            boolean success = executeConcatCommand(listFile.getAbsolutePath(), outputPath, true);
            if (!success) {
                log.warn("流复制模式拼接失败，尝试重新编码方式");
                success = executeConcatCommand(listFile.getAbsolutePath(), outputPath, false);
            }

            if (!success) {
                throw new RuntimeException("视频拼接失败");
            }

            log.info("视频拼接完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        } finally {
            if (listFile != null && listFile.exists()) {
                boolean deleted = listFile.delete();
                log.debug("删除临时列表文件: path={}, result={}", listFile.getAbsolutePath(), deleted);
            }
        }
    }

    private static boolean executeConcatCommand(String listFilePath, String outputPath, boolean streamCopy) throws java.io.IOException, InterruptedException {
        ProcessBuilder processBuilder;
        if (streamCopy) {
            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFilePath,
                    "-c:v", "copy",
                    "-c:a", "copy",
                    "-y",
                    outputPath
            );
            log.info("使用流复制模式执行视频拼接");
        } else {
            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFilePath,
                    "-c:v", "libx264",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-y",
                    outputPath
            );
            log.info("使用重新编码模式执行视频拼接");
        }

        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);
        if (!completed) {
            log.error("FFmpeg处理超时，强制终止进程");
            process.destroyForcibly();
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("FFmpeg执行失败，退出码: {}, 输出信息: {}", exitCode, output);
            return false;
        }

        return true;
    }

    /**
     * WebM转MP4格式转换
     * <p>将WebM格式视频转换为MP4格式，使用H.264视频编码和AAC音频编码。</p>
     * <p>命令格式: ffmpeg -i input.webm -c:v libx264 -c:a aac -b:a 192k -y output.mp4</p>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查视频路径、文件存在性、FFmpeg可用性
     * 2. 格式检查：验证输入文件是否为WebM格式
     * 3. 生成输出路径：在原文件名基础上替换扩展名
     * 4. 执行转换：使用FFmpeg将WebM转换为MP4
     * 5. 结果验证：检查输出文件是否生成成功
     * </pre>
     * 
     * <h3>关键技术说明：</h3>
     * <ul>
     *   <li>视频编码：使用libx264编码器，保证MP4兼容性</li>
     *   <li>音频编码：使用AAC编码器，比特率192k，保证音频质量</li>
     *   <li>超时控制：转换超时10分钟，防止长时间阻塞</li>
     *   <li>覆盖输出：使用-y参数覆盖已存在的输出文件</li>
     * </ul>
     * 
     * @param webmPath WebM格式视频文件路径
     * @return 转换后的MP4格式视频文件路径
     * @throws IllegalArgumentException 当视频路径为空时抛出
     * @throws RuntimeException 当视频文件不存在、FFmpeg不可用、处理超时或失败时抛出
     */
    public static String convertWebmToMp4(String webmPath) {
        log.info("执行WebM转MP4格式转换，输入路径: {}", webmPath);

        if (webmPath == null || webmPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        File webmFile = new File(webmPath);
        if (!webmFile.exists()) {
            log.error("WebM文件不存在: {}", webmPath);
            throw new RuntimeException("WebM文件不存在: " + webmPath);
        }

        if (!webmPath.toLowerCase().endsWith(".webm")) {
            log.warn("输入文件格式不是 WebM，可能影响转换: {}", webmPath);
        }

        String outputPath = webmPath.substring(0, webmPath.lastIndexOf('.')) + ".mp4";

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", webmPath,
                    "-c", "copy",
                    "-y",
                    outputPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("FFmpeg处理超时，强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg处理超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("FFmpeg执行失败，退出码: {}, 错误信息: {}", exitCode, output);
                throw new RuntimeException("FFmpeg执行失败: " + output);
            }

            File outputFile = new File(outputPath);
            if (!outputFile.exists()) {
                log.error("转换后的MP4文件不存在: {}", outputPath);
                throw new RuntimeException("转换后的MP4文件不存在: " + outputPath);
            }

            log.info("WebM转MP4格式转换完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("FFmpeg处理被中断", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        }
    }

    /**
     * WebP转MP4格式转换
     * 
     * <p>使用Python脚本将WebP图片转换为MP4视频格式。</p>
     * <p>支持动态WebP的多帧提取，使用PIL/Pillow提取帧，cv2/OpenCV编码MP4。</p>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查图片路径、文件存在性
     * 2. 格式检查：验证输入文件是否为WebP格式
     * 3. 生成输出路径：在原文件名基础上替换扩展名
     * 4. 生成Python脚本：创建临时脚本处理帧提取和编码
     * 5. 执行转换：运行Python脚本将WebP转为MP4
     * 6. 结果验证：检查输出文件是否生成成功
     * 7. 清理：删除临时Python脚本
     * </pre>
     * 
     * <h3>关键技术说明：</h3>
     * <ul>
     *   <li>帧提取：使用PIL的seek/tell机制遍历WebP所有帧</li>
     *   <li>颜色空间：RGB转BGR适配OpenCV的颜色空间要求</li>
     *   <li>视频编码：使用mp4v编码器，兼容性好</li>
     *   <li>帧率设置：24fps，保证流畅度</li>
     *   <li>超时控制：转换超时5分钟，防止长时间阻塞</li>
     * </ul>
     * 
     * @param webpPath WebP格式图片文件路径
     * @return 转换后的MP4视频文件路径
     * @throws IllegalArgumentException 当图片路径为空时抛出
     * @throws RuntimeException 当图片文件不存在、处理超时或失败时抛出
     */
    public static String convertWebpToMp4(String webpPath) {
        log.info("执行WebP转MP4格式转换，输入路径: {}", webpPath);

        if (webpPath == null || webpPath.isEmpty()) {
            log.error("图片路径为空");
            throw new IllegalArgumentException("图片路径不能为空");
        }

        File webpFile = new File(webpPath);
        if (!webpFile.exists()) {
            log.error("WebP文件不存在: {}", webpPath);
            throw new RuntimeException("WebP文件不存在: " + webpPath);
        }

        if (!webpPath.toLowerCase().endsWith(".webp")) {
            log.warn("输入文件格式不是 WebP，可能影响转换: {}", webpPath);
        }

        String outputPath = webpPath.substring(0, webpPath.lastIndexOf('.')) + ".mp4";

        File tempScript = null;

        try {
            // 生成临时Python脚本
            String pythonScript = "from PIL import Image\n" +
                    "import numpy as np\n" +
                    "import cv2\n" +
                    "import sys\n" +
                    "\n" +
                    "input_path = sys.argv[1]\n" +
                    "output_path = sys.argv[2]\n" +
                    "\n" +
                    "img = Image.open(input_path)\n" +
                    "frames = []\n" +
                    "while True:\n" +
                    "    try:\n" +
                    "        frames.append(np.array(img.convert('RGB')))\n" +
                    "        img.seek(img.tell() + 1)\n" +
                    "    except EOFError:\n" +
                    "        break\n" +
                    "\n" +
                    "if len(frames) == 0:\n" +
                    "    print('错误: 无法从WebP文件中提取帧')\n" +
                    "    sys.exit(1)\n" +
                    "\n" +
                    "h, w, _ = frames[0].shape\n" +
                    "fourcc = cv2.VideoWriter_fourcc(*'mp4v')\n" +
                    "out = cv2.VideoWriter(output_path, fourcc, 24.0, (w, h))\n" +
                    "for frame in frames:\n" +
                    "    out.write(cv2.cvtColor(frame, cv2.COLOR_RGB2BGR))\n" +
                    "out.release()\n" +
                    "print(f'转换完成！共 {len(frames)} 帧')\n";

            // 写入临时Python文件
            tempScript = File.createTempFile("webp_convert_", ".py");
            tempScript.deleteOnExit();
            try (java.io.FileWriter writer = new java.io.FileWriter(tempScript)) {
                writer.write(pythonScript);
            }

            log.info("Python转换脚本已生成: {}", tempScript.getAbsolutePath());

            // 执行Python脚本
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "python",
                    tempScript.getAbsolutePath(),
                    webpPath,
                    outputPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("Python转换处理超时，强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("Python转换处理超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Python转换执行失败，退出码: {}, 错误信息: {}", exitCode, output);
                throw new RuntimeException("Python转换执行失败: " + output);
            }

            File outputFile = new File(outputPath);
            if (!outputFile.exists()) {
                log.error("转换后的MP4文件不存在: {}", outputPath);
                throw new RuntimeException("转换后的MP4文件不存在: " + outputPath);
            }

            log.info("WebP转MP4格式转换完成，输出路径: {}, 详情: {}", outputPath, output);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行Python脚本失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行Python脚本失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("Python转换被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("Python转换被中断", e);
        } finally {
            // 清理临时脚本
            if (tempScript != null && tempScript.exists()) {
                boolean deleted = tempScript.delete();
                if (deleted) {
                    log.debug("临时Python脚本已清理: {}", tempScript.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 统一转换为MP4格式
     * 
     * <p>根据文件类型自动选择对应的转换方法：</p>
     * <ul>
     *   <li>.mp4 → 直接返回原路径（无需转换）</li>
     *   <li>.webp → 调用 convertWebpToMp4（Python脚本方式）</li>
     *   <li>.webm → 调用 convertWebmToMp4（FFmpeg copy方式）</li>
     *   <li>其他格式 → 直接返回原路径</li>
     * </ul>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查文件路径、文件存在性
     * 2. 类型判断：根据文件扩展名识别格式
     * 3. 路由转换：调用对应的转换函数
     * 4. 返回结果：返回转换后的MP4路径
     * </pre>
     * 
     * @param filePath 输入文件路径（支持.mp4、.webp和.webm格式）
     * @return 转换后的MP4文件路径
     * @throws IllegalArgumentException 当文件路径为空时抛出
     * @throws RuntimeException 当文件不存在或转换失败时抛出
     */
    public static String convertToMp4(String filePath) {
        log.info("执行统一格式转换，输入路径: {}", filePath);

        if (filePath == null || filePath.isEmpty()) {
            log.error("文件路径为空");
            throw new IllegalArgumentException("文件路径不能为空");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            log.error("文件不存在: {}", filePath);
            throw new RuntimeException("文件不存在: " + filePath);
        }

        String lowerPath = filePath.toLowerCase();

        if (lowerPath.endsWith(".mp4")) {
            log.info("检测到已是MP4格式，直接返回原路径");
            return filePath;
        } else if (lowerPath.endsWith(".webp")) {
            log.info("检测到WebP格式，调用WebP转MP4转换");
            return convertWebpToMp4(filePath);
        } else if (lowerPath.endsWith(".webm")) {
            log.info("检测到WebM格式，调用WebM转MP4转换");
            return convertWebmToMp4(filePath);
        } else {
            log.warn("不支持的文件格式，直接返回原路径: {}", filePath);
            return filePath;
        }
    }

    /**
     * 调整FLAC语音文件语速
     * <p>使用FFmpeg的atempo滤镜调整FLAC音频文件的语速。</p>
     * <p>命令格式: ffmpeg -i input.flac -filter:a "atempo=1.3" output.flac</p>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查音频路径、文件存在性、FFmpeg可用性
     * 2. 语速校验：确认语速参数在有效范围内（0.5~2.0）
     * 3. 格式检查：验证输入文件是否为FLAC格式
     * 4. 生成输出路径：在原文件名基础上添加_speed后缀
     * 5. 执行转换：使用FFmpeg atempo滤镜调整语速
     * 6. 结果验证：检查输出文件是否生成成功
     * </pre>
     * 
     * <h3>关键技术说明：</h3>
     * <ul>
     *   <li>atempo滤镜：FFmpeg原生语速调整，保持音质不变</li>
     *   <li>语速范围：支持0.5（慢速）到2.0（快速）</li>
     *   <li>默认语速：1.3倍速，适合解说语音</li>
     *   <li>超时控制：处理超时5分钟，防止长时间阻塞</li>
     * </ul>
     * 
     * @param flacPath FLAC音频文件路径
     * @param speed 语速倍率，默认1.3，范围0.5~2.0
     * @return 调整语速后的FLAC音频文件路径
     * @throws IllegalArgumentException 当路径为空、语速超出范围时抛出
     * @throws RuntimeException 当文件不存在、FFmpeg不可用、处理超时或失败时抛出
     */
    public static String adjustFlacSpeed(String flacPath, double speed) {
        log.info("执行FLAC语速调整，输入路径: {}, 语速: {}", flacPath, speed);

        if (flacPath == null || flacPath.isEmpty()) {
            log.error("音频路径为空");
            throw new IllegalArgumentException("音频路径不能为空");
        }

        if (speed < 0.5 || speed > 2.0) {
            log.error("语速超出有效范围: {}, 有效范围: 0.5~2.0", speed);
            throw new IllegalArgumentException("语速必须在0.5~2.0范围内");
        }

        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        File flacFile = new File(flacPath);
        if (!flacFile.exists()) {
            log.error("FLAC文件不存在: {}", flacPath);
            throw new RuntimeException("FLAC文件不存在: " + flacPath);
        }

        if (!flacPath.toLowerCase().endsWith(".flac")) {
            log.warn("输入文件格式不是 FLAC，可能影响处理: {}", flacPath);
        }

        String outputPath = flacPath.substring(0, flacPath.lastIndexOf('.')) + "_" + speed + ".flac";

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", flacPath,
                    "-filter:a", "atempo=" + speed,
                    "-y",
                    outputPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("FFmpeg处理超时，强制终止进程");
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg处理超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("FFmpeg执行失败，退出码: {}, 错误信息: {}", exitCode, output);
                throw new RuntimeException("FFmpeg执行失败: " + output);
            }

            File outputFile = new File(outputPath);
            if (!outputFile.exists()) {
                log.error("调整语速后的FLAC文件不存在: {}", outputPath);
                throw new RuntimeException("调整语速后的FLAC文件不存在: " + outputPath);
            }

            log.info("FLAC语速调整完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        }
    }

    /**
     * 调整FLAC语音文件语速（使用默认语速1.3倍速）
     * 
     * @param flacPath FLAC音频文件路径
     * @return 调整语速后的FLAC音频文件路径
     */
    public static String adjustFlacSpeed(String flacPath) {
        return adjustFlacSpeed(flacPath, 1.3);
    }


    private static String generateOutputPath(String inputPath, String suffix) {
        Path path = Paths.get(inputPath);
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = fileName.substring(0, dotIndex);
            String extension = fileName.substring(dotIndex);
            return path.getParent().resolve(baseName + suffix + extension).toString();
        } else {
            return path.getParent().resolve(fileName + suffix).toString();
        }
    }

    private static String escapePath(String path) {
        return path.replace("\\", "\\\\")
                   .replace(":", "\\:")
                   .replace("'", "\\'")
                   .replace("\"", "\\\"");
    }
    
    /**
     * 将文件复制到安全位置
     * <p>用于处理ComfyUI临时文件，避免被ComfyUI自动清理。</p>
     * 
     * @param sourcePath 源文件路径
     * @return 复制后的安全路径
     * @throws RuntimeException 当文件复制失败时抛出
     */
    private static String copyToSafeLocation(String sourcePath) {
        try {
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                log.error("源文件不存在: {}", sourcePath);
                throw new RuntimeException("源文件不存在: " + sourcePath);
            }
            
            // 获取文件扩展名
            String extension = "";
            int dotIndex = sourceFile.getName().lastIndexOf('.');
            if (dotIndex > 0) {
                extension = sourceFile.getName().substring(dotIndex);
            }
            
            // 生成新文件名：使用时间戳避免冲突
            String newFileName = "audio_" + System.currentTimeMillis() + extension;
            
            // 目标路径：与源文件同目录，但文件名不同
            String targetPath = sourceFile.getParent() + File.separator + newFileName;
            File targetFile = new File(targetPath);
            
            // 复制文件
            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            log.info("文件已复制到安全位置: {} -> {}", sourcePath, targetPath);
            return targetPath;
            
        } catch (java.io.IOException e) {
            log.error("文件复制失败: {}", sourcePath, e);
            throw new RuntimeException("文件复制失败: " + e.getMessage(), e);
        }
    }
}