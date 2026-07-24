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
     *
     * @param videoDesignResponse 视频设计响应，使用English字段作为提示词
     * @param imagePath 图片路径
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该视频生成任务属于哪个解说视频生成任务
     * @return 生成的视频文件路径
     */
    public String generateVideoWithAudio(VideoDesignResponse videoDesignResponse, String imagePath, String userId, String fatherTaskId) {
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

        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                log.error("图片文件不存在: {}", imagePath);
                throw new RuntimeException("图片文件不存在: " + imagePath);
            }

            FileMultipartFile multipartFile = new FileMultipartFile(imageFile);

            String taskId = taskService.submitTask(
                    userId,
                    TaskType.IMAGE_TO_VIDEO_AUDIO,
                    description,
                    multipartFile,
                    null,
                    null,
                    videoDesignResponse.getEstimatedDuration(),
                    false,
                    fatherTaskId
            );
            log.info("带音频视频生成任务已提交，任务ID: {}", taskId);

            long startTime = System.currentTimeMillis();
            long timeoutMs = 30 * 60 * 1000;   // 超时时间，半小时

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    Thread.sleep(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("带音频视频生成成功，文件路径: {}", downloadPath);
                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    log.error("带音频视频生成任务失败");
                    throw new RuntimeException("带音频视频生成任务失败");
                }

                Thread.sleep(3000);
            }

            log.error("带音频视频生成任务超时");
            throw new RuntimeException("带音频视频生成任务超时");

        } catch (Exception e) {
            log.error("带音频视频生成失败", e);
            throw new RuntimeException("带音频视频生成失败", e);
        }
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

        try {
            String taskId = taskService.submitTask(
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
            long timeoutMs = 30 * 60 * 1000;   // 超时时间，半小时

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    Thread.sleep(3000);
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
                    log.error("图片生成任务失败");
                    throw new RuntimeException("图片生成任务失败");
                }

                Thread.sleep(3000);
            }

            log.error("图片生成任务超时");
            throw new RuntimeException("图片生成任务超时");

        } catch (Exception e) {
            log.error("图片生成失败", e);
            throw new RuntimeException("图片生成失败", e);
        }
    }

    /**
     * 文字生成语音
     *
     * <p>根据旁白内容生成语音，调用ComfyUI工作流进行文生语音。</p>
     *
     * @param narrationItem 旁白项，包含text、emotion、speaker字段
     * @param userId 用户ID
     * @param fatherTaskId 父任务ID，记录该语音生成任务属于哪个解说视频生成任务
     * @return 生成的语音文件路径
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

        try {
            String taskId = taskService.submitTask(
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
            long timeoutMs = 30 * 60 * 1000;   // 设置超时时间，半小时

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                Task task = taskService.getTaskEntity(taskId);
                if (task == null) {
                    log.warn("任务暂未查询到，等待中...");
                    Thread.sleep(3000);
                    continue;
                }

                String status = task.getStatus();
                log.debug("任务状态: {}, 进度: {}%", status, task.getProgress());

                if ("执行成功".equals(status)) {
                    String downloadPath = task.getDownloadPath();
                    log.info("语音生成成功，文件路径: {}", downloadPath);
                    
                    // 如果是ComfyUI临时文件，复制到安全位置避免被清理
                    if (downloadPath != null && downloadPath.contains("ComfyUI_temp_")) {
                        String safePath = copyToSafeLocation(downloadPath);
                        log.info("已将临时音频文件复制到安全位置: {}", safePath);
                        return safePath;
                    }
                    
                    return downloadPath;
                }

                if ("执行失败".equals(status)) {
                    log.error("语音生成任务失败");
                    throw new RuntimeException("语音生成任务失败");
                }

                Thread.sleep(3000);
            }

            log.error("语音生成任务超时");
            throw new RuntimeException("语音生成任务超时");

        } catch (Exception e) {
            log.error("语音生成失败", e);
            throw new RuntimeException("语音生成失败", e);
        }
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
     * 音视频整合
     *
     * <p>将音频文件与视频文件合并。</p>
     *
     * @param videoPath 视频文件路径
     * @param audioPath 音频文件路径
     * @return 合成后的视频文件路径
     */
    public static String mergeAudioVideo(String videoPath, String audioPath) {
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
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-i", audioPath,
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-shortest",
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
     * 字幕生成
     * <p>从视频文件中自动识别语音内容并生成SRT格式字幕。</p>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查视频路径、文件存在性、FFmpeg和Whisper可用性
     * 2. 音频提取：使用FFmpeg从视频中提取音频，转换为WAV格式（16kHz, 单声道, PCM编码）
     * 3. 语音识别：调用Whisper CLI对提取的音频进行语音识别，生成SRT字幕文件
     * 4. 结果读取：读取Whisper生成的SRT文件内容并返回
     * 5. 资源清理：在finally块中删除临时音频文件和生成的SRT文件
     * </pre>
     * 
     * <h3>关键技术说明：</h3>
     * <ul>
     *   <li>音频格式转换：将视频中的音频提取为16kHz采样率、单声道、PCM_S16LE编码的WAV文件，这是Whisper推荐的输入格式</li>
     *   <li>SRT文件命名：Whisper根据输入音频文件名生成SRT文件（如 audio_12345.wav → audio_12345.srt），而非根据视频文件名</li>
     *   <li>超时控制：音频提取超时60秒，Whisper识别超时10分钟（考虑到长视频识别耗时）</li>
     *   <li>异常处理：任何步骤失败都会抛出RuntimeException，并在finally块中确保临时文件被清理</li>
     * </ul>
     * 
     * @param videoPath 视频文件路径（支持MP4等常见视频格式）
     * @return 字幕内容（标准SRT格式字符串，包含时间戳和文本内容）
     * @throws IllegalArgumentException 当视频路径为空时抛出
     * @throws RuntimeException 当视频文件不存在、FFmpeg/Whisper不可用、处理超时或失败时抛出
     */
    public static String generateSubtitles(String videoPath) {
        log.info("开始执行字幕生成，视频路径: {}", videoPath);

        // ========== 输入验证阶段 ==========
        if (videoPath == null || videoPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
        }

        // 检查FFmpeg是否可用（用于提取音频）
        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        // 检查Whisper是否可用（用于语音识别）
        if (!isWhisperAvailable()) {
            throw new RuntimeException("Whisper 未安装或不可用");
        }

        // 获取输出目录，Whisper生成的SRT文件将保存在此目录
        String outputDir = videoFile.getParent();
        if (outputDir == null) {
            outputDir = System.getProperty("java.io.tmpdir");
            log.warn("视频文件无父目录，使用系统临时目录: {}", outputDir);
        }

        // 声明需要在finally块中清理的资源
        File tempAudioFile = null;      // FFmpeg提取的临时WAV音频文件
        File generatedSrtFile = null;    // Whisper生成的SRT字幕文件

        try {
            // ========== 步骤1：从视频中提取音频 ==========
            // 创建临时WAV文件，用于存放提取的音频
            tempAudioFile = File.createTempFile("audio_", ".wav");
            log.info("创建临时音频文件: {}", tempAudioFile.getAbsolutePath());

            // FFmpeg命令参数说明：
            // -i: 输入文件
            // -vn: 禁用视频流（只提取音频）
            // -acodec pcm_s16le: 使用PCM 16位小端编码（Whisper推荐格式）
            // -ar 16000: 设置采样率为16kHz（Whisper推荐采样率）
            // -ac 1: 设置为单声道
            // -f wav: 指定输出格式为WAV
            // -y: 覆盖已存在的输出文件
            ProcessBuilder extractBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-vn",
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    "-f", "wav",
                    "-y",
                    tempAudioFile.getAbsolutePath()
            );

            // 将标准错误流重定向到标准输出流，统一读取
            extractBuilder.redirectErrorStream(true);
            Process extractProcess = extractBuilder.start();

            // 读取FFmpeg的输出日志
            StringBuilder extractOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(extractProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    extractOutput.append(line).append("\n");
                }
            }

            // 等待FFmpeg完成，设置60秒超时
            boolean extractCompleted = extractProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!extractCompleted) {
                log.error("FFmpeg提取音频超时，强制终止进程");
                extractProcess.destroyForcibly();
                throw new RuntimeException("FFmpeg提取音频超时");
            }

            // 检查FFmpeg退出码，非0表示失败
            int extractExitCode = extractProcess.exitValue();
            if (extractExitCode != 0) {
                log.error("FFmpeg提取音频失败，退出码: {}, 输出信息: {}", extractExitCode, extractOutput);
                throw new RuntimeException("FFmpeg提取音频失败");
            }

            log.info("音频提取完成，临时文件大小: {} bytes", tempAudioFile.length());

            // ========== 步骤2：使用Whisper进行语音识别 ==========
            log.info("开始调用Whisper进行语音识别");

            // Whisper CLI参数说明：
            // --model base: 使用base模型（速度较快，准确率适中）
            // --language Chinese: 指定语言为中文
            // --output_format srt: 输出格式为SRT字幕
            // --output_dir: 指定输出目录
            ProcessBuilder whisperBuilder = new ProcessBuilder(
                    "whisper",
                    tempAudioFile.getAbsolutePath(),
                    "--model", "base",
                    "--language", "Chinese",
                    "--output_format", "srt",
                    "--output_dir", outputDir
            );

            // 将标准错误流重定向到标准输出流，统一读取
            whisperBuilder.redirectErrorStream(true);
            Process whisperProcess = whisperBuilder.start();

            // 读取Whisper的输出日志
            StringBuilder whisperOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(whisperProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    whisperOutput.append(line).append("\n");
                }
            }

            // 等待Whisper完成，设置10分钟超时（语音识别耗时较长）
            boolean completed = whisperProcess.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                log.error("Whisper处理超时，强制终止进程");
                whisperProcess.destroyForcibly();
                throw new RuntimeException("Whisper处理超时");
            }

            // 检查Whisper退出码，非0表示失败
            int exitCode = whisperProcess.exitValue();
            if (exitCode != 0) {
                log.error("Whisper执行失败，退出码: {}, 输出信息: {}", exitCode, whisperOutput);
                throw new RuntimeException("Whisper执行失败: " + whisperOutput);
            }

            // ========== 步骤3：读取生成的SRT文件 ==========
            // 关键注意点：Whisper生成的SRT文件名基于输入音频文件名，而非视频文件名
            // 例如：输入 audio_12345.wav → 生成 audio_12345.srt
            String audioFileName = tempAudioFile.getName();
            String srtFileName = audioFileName.substring(0, audioFileName.lastIndexOf('.')) + ".srt";
            String srtPath = outputDir + File.separator + srtFileName;
            generatedSrtFile = new File(srtPath);

            // 验证SRT文件是否生成成功
            if (generatedSrtFile.exists()) {
                String srtContent = new String(Files.readAllBytes(generatedSrtFile.toPath()), StandardCharsets.UTF_8);
                log.info("字幕生成完成，SRT文件路径: {}, 内容长度: {} 字符", srtPath, srtContent.length());
                return srtContent;
            } else {
                log.error("Whisper执行成功但未生成SRT文件，期望路径: {}", srtPath);
                throw new RuntimeException("Whisper未生成字幕文件");
            }

        } catch (java.io.IOException e) {
            // IO异常：文件创建失败、命令执行失败等
            log.error("执行字幕生成命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("字幕生成失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // 线程中断异常：处理过程被外部中断
            log.warn("字幕生成被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("字幕生成被中断", e);
        } finally {
            // ========== 资源清理阶段 ==========
            // 删除临时音频文件
            if (tempAudioFile != null && tempAudioFile.exists()) {
                boolean deleted = tempAudioFile.delete();
                log.debug("删除临时音频文件: path={}, 结果={}", tempAudioFile.getAbsolutePath(), deleted);
            }
            // 删除Whisper生成的SRT文件（已读取内容，不再需要）
            if (generatedSrtFile != null && generatedSrtFile.exists()) {
                boolean deleted = generatedSrtFile.delete();
                log.debug("删除生成的SRT文件: path={}, 结果={}", generatedSrtFile.getAbsolutePath(), deleted);
            }
        }
    }

    /**
     * 检查Whisper语音识别工具是否可用
     * <p>通过执行 whisper --help 命令来验证Whisper是否已安装并可在系统路径中找到。</p>
     * 
     * @return true表示Whisper可用，false表示不可用
     */
    private static boolean isWhisperAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("whisper", "--help");
            Process process = processBuilder.start();
            // 设置5秒超时，避免长时间等待
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Whisper 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 字幕烧录
     * <p>将字幕内容烧录（硬编码）到视频中，生成带字幕的视频文件。</p>
     * 
     * <h3>处理流程：</h3>
     * <pre>
     * 1. 输入验证：检查视频路径、文件存在性、FFmpeg可用性
     * 2. 字幕文件创建：将字幕内容字符串写入临时SRT文件
     * 3. 字幕烧录：使用FFmpeg的subtitles滤镜将字幕渲染到视频帧
     * 4. 资源清理：删除临时字幕文件
     * </pre>
     * 
     * <h3>关键技术说明：</h3>
     * <ul>
     *   <li>硬编码方式：字幕被直接渲染到视频画面上，无法关闭或提取</li>
     *   <li>视频重编码：使用-vf滤镜需要对视频进行重新编码，处理时间较长</li>
     *   <li>路径转义：字幕文件路径中的特殊字符（如冒号、反斜杠）需要转义</li>
     * </ul>
     * 
     * @param videoPath 视频文件路径（支持MP4等常见视频格式）
     * @param subtitles 字幕内容字符串（标准SRT格式）
     * @return 烧录字幕后的视频文件路径
     * @throws IllegalArgumentException 当视频路径或字幕内容为空时抛出
     * @throws RuntimeException 当视频文件不存在、FFmpeg不可用、处理超时或失败时抛出
     */
    public static String burnSubtitles(String videoPath, String subtitles) {
        log.info("执行字幕烧录，视频路径: {}", videoPath);

        // ========== 输入验证阶段 ==========
        if (videoPath == null || videoPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        if (subtitles == null || subtitles.isEmpty()) {
            log.error("字幕内容为空");
            throw new IllegalArgumentException("字幕内容不能为空");
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
        }

        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            log.warn("视频文件格式不是 MP4，可能影响处理: {}", videoPath);
        }

        // 检查FFmpeg是否可用
        if (!isFfmpegAvailable()) {
            throw new RuntimeException("FFmpeg 未安装或不可用");
        }

        String outputPath = generateOutputPath(videoPath, "_with_subtitles");
        File srtFile = null;

        try {
            // ========== 步骤1：创建临时字幕文件 ==========
            // 将字幕内容字符串写入临时SRT文件
            srtFile = File.createTempFile("subtitles_", ".srt");
            log.info("创建临时字幕文件: {}", srtFile.getAbsolutePath());

            Files.write(srtFile.toPath(), subtitles.getBytes(StandardCharsets.UTF_8));

            // ========== 步骤2：执行FFmpeg字幕烧录 ==========
            // FFmpeg命令参数说明：
            // -i: 输入视频文件
            // -vf subtitles=: 使用subtitles滤镜加载字幕文件
            // -c:v libx264: 视频编码器（使用-vf滤镜需要重新编码）
            // -preset medium: 编码速度与质量的平衡
            // -crf 23: 恒定质量因子（数值越小质量越高）
            // -c:a copy: 音频流直接复制，不重新编码
            // -y: 覆盖已存在的输出文件
            String filterComplex = "subtitles=" + escapePath(srtFile.getAbsolutePath());

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-vf", filterComplex,
                    "-c:v", "libx264",
                    "-preset", "medium",
                    "-crf", "23",
                    "-c:a", "copy",
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

            // 设置15分钟超时（字幕烧录需要重新编码视频，耗时较长）
            boolean completed = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);
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

            // 验证输出文件是否生成成功
            File outputFile = new File(outputPath);
            if (!outputFile.exists()) {
                log.error("字幕烧录完成但输出文件不存在: {}", outputPath);
                throw new RuntimeException("字幕烧录失败：输出文件未生成");
            }

            log.info("字幕烧录完成，输出路径: {}, 文件大小: {} bytes", outputPath, outputFile.length());
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        } finally {
            // ========== 资源清理阶段 ==========
            // 删除临时字幕文件
            if (srtFile != null && srtFile.exists()) {
                boolean deleted = srtFile.delete();
                log.debug("删除临时字幕文件: path={}, 结果={}", srtFile.getAbsolutePath(), deleted);
            }
        }
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