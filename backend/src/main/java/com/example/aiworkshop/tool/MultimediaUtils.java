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

        File inputFile = new File(videoPath);
        if (!inputFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
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

        String outputPath = generateOutputPath(videoPath, "_with_audio");

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-i", audioPath,
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-strict", "experimental",
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
     * ffmpeg -f concat -safe 0 -i list.txt -c:v copy -c:a copy -y output_concat.mp4
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
        }

        String firstVideoPath = videoPaths[0];
        String outputPath = generateOutputPath(firstVideoPath, "_concat");

        File listFile = null;
        try {
            listFile = File.createTempFile("ffmpeg_concat_", ".txt");
            log.info("创建临时列表文件: {}", listFile.getAbsolutePath());

            try (java.io.PrintWriter writer = new java.io.PrintWriter(listFile)) {
                for (String videoPath : videoPaths) {
                    writer.println("file '" + videoPath + "'");
                }
            }
            log.info("临时列表文件写入完成，共{}个视频片段", videoPaths.length);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.getAbsolutePath(),
                    "-c:v", "copy",
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

    /**
     * 字幕生成
     * 输入视频 → FFmpeg提取音频 → 临时WAV文件 → Whisper生成字幕 → 返回SRT内容
     * <p>从视频中提取音频并生成字幕。</p>
     * 使用whisper生成字幕文件
     * @param videoPath 视频文件路径
     * @return 字幕内容（SRT格式）
     */
    public static String generateSubtitles(String videoPath) {
        log.info("执行字幕生成，视频路径: {}", videoPath);

        if (videoPath == null || videoPath.isEmpty()) {
            log.error("视频路径为空");
            throw new IllegalArgumentException("视频路径不能为空");
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            log.error("视频文件不存在: {}", videoPath);
            throw new RuntimeException("视频文件不存在: " + videoPath);
        }

        File tempAudioFile = null;

        try {
            tempAudioFile = File.createTempFile("audio_", ".wav");
            log.info("创建临时音频文件: {}", tempAudioFile.getAbsolutePath());

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

            extractBuilder.redirectErrorStream(true);
            Process extractProcess = extractBuilder.start();

            StringBuilder extractOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(extractProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    extractOutput.append(line).append("\n");
                }
            }

            boolean extractCompleted = extractProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!extractCompleted) {
                log.error("FFmpeg提取音频超时，强制终止进程");
                extractProcess.destroyForcibly();
                throw new RuntimeException("FFmpeg提取音频超时");
            }

            int extractExitCode = extractProcess.exitValue();
            if (extractExitCode != 0) {
                log.error("FFmpeg提取音频失败，退出码: {}, 输出: {}", extractExitCode, extractOutput);
                throw new RuntimeException("FFmpeg提取音频失败");
            }

            log.info("音频提取完成，开始生成字幕");

            String outputDir = videoFile.getParent();
            String outputPath = generateOutputPath(videoPath, "_subtitles");

            ProcessBuilder whisperBuilder = new ProcessBuilder(
                    "whisper",
                    tempAudioFile.getAbsolutePath(),
                    "--model", "base",
                    "--language", "Chinese",
                    "--output_format", "srt",
                    "--output_dir", outputDir
            );

            whisperBuilder.redirectErrorStream(true);
            Process whisperProcess = whisperBuilder.start();

            StringBuilder whisperOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(whisperProcess.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    whisperOutput.append(line).append("\n");
                }
            }

            boolean completed = whisperProcess.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                log.error("Whisper处理超时，强制终止进程");
                whisperProcess.destroyForcibly();
                throw new RuntimeException("Whisper处理超时");
            }

            int exitCode = whisperProcess.exitValue();
            if (exitCode != 0) {
                log.error("Whisper执行失败，退出码: {}, 输出: {}", exitCode, whisperOutput);
                throw new RuntimeException("Whisper执行失败: " + whisperOutput);
            }

            String srtPath = outputPath + ".srt";
            File srtFile = new File(srtPath);
            if (srtFile.exists()) {
                String srtContent = new String(Files.readAllBytes(srtFile.toPath()), StandardCharsets.UTF_8);
                log.info("字幕生成完成，SRT文件路径: {}", srtPath);
                return srtContent;
            } else {
                log.error("Whisper执行成功但未生成SRT文件");
                throw new RuntimeException("Whisper未生成字幕文件");
            }

        } catch (java.io.IOException e) {
            log.error("执行Whisper命令失败: {}", e.getMessage());
            throw new RuntimeException("字幕生成失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("字幕生成被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("字幕生成被中断", e);
        } finally {
            if (tempAudioFile != null && tempAudioFile.exists()) {
                tempAudioFile.delete();
                log.debug("删除临时音频文件");
            }
        }
    }

    /**
     * 字幕烧录
     *
     * <p>将字幕烧录到视频中。</p>
     *
     * @param videoPath 视频文件路径
     * @param subtitles 字幕内容
     * @return 烧录字幕后的视频文件路径
     */
    public static String burnSubtitles(String videoPath, String subtitles) {
        log.info("执行字幕烧录，视频路径: {}", videoPath);

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

        String outputPath = generateOutputPath(videoPath, "_with_subtitles");
        File srtFile = null;

        try {
            srtFile = File.createTempFile("subtitles_", ".srt");
            log.info("创建临时字幕文件: {}", srtFile.getAbsolutePath());

            Files.write(srtFile.toPath(), subtitles.getBytes(StandardCharsets.UTF_8));

            String filterComplex = "subtitles=" + escapePath(srtFile.getAbsolutePath());

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-i", videoPath,
                    "-vf", filterComplex,
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

            log.info("字幕烧录完成，输出路径: {}", outputPath);
            return outputPath;

        } catch (java.io.IOException e) {
            log.error("执行FFmpeg命令失败: {}", e.getMessage(), e);
            throw new RuntimeException("执行FFmpeg命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("FFmpeg处理被中断");
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg处理被中断", e);
        } finally {
            if (srtFile != null && srtFile.exists()) {
                boolean deleted = srtFile.delete();
                log.debug("删除临时字幕文件: path={}, result={}", srtFile.getAbsolutePath(), deleted);
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
}