package com.example.aiworkshop.service;

import com.example.aiworkshop.dto.response.PageResponse;
import com.example.aiworkshop.dto.response.TaskResponse;
import com.example.aiworkshop.entity.Task;
import com.example.aiworkshop.entity.TaskType;
import com.example.aiworkshop.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final LlmService llmService;
    private final QwenMultiModalService qwenMultiModalService;
    private final com.example.aiworkshop.tool.VideoGenerateTools videoGenerateTools;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    
    @Lazy
    @Autowired
    private com.example.aiworkshop.tool.MultimediaUtils multimediaUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClients.createDefault();

    @Value("${file.upload-dir:/root/comfyui/storage-user/input}")
    private String uploadDir;

    @Value("${file.output-dir:/root/comfyui/storage-user/output}")
    private String outputDir;

    @Value("${comfyui.url:http://localhost:8188}")
    private String comfyuiUrl;

    @Value("${comfyui.poll-interval:60000}")
    private long pollInterval;

    @Value("${workflow.dir:workflows}")
    private String workflowDir;

    @Value("${comfyui.max-concurrent-tasks:1}")
    private int maxConcurrentTasks;

    private static final int DEFAULT_VIDEO_DURATION_SHORT = 3;
    private static final int DEFAULT_VIDEO_DURATION_LONG = 10;

    private final BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    private final Map<String, Task> taskCache = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> taskLatches = new ConcurrentHashMap<>();
    private final java.util.List<Thread> workerThreads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        log.info("========== TaskService 初始化开始 ==========");
        log.info("配置信息 - uploadDir={}", uploadDir);
        log.info("配置信息 - outputDir={}", outputDir);
        log.info("配置信息 - comfyuiUrl={}", comfyuiUrl);
        log.info("配置信息 - pollInterval={}ms", pollInterval);
        log.info("配置信息 - workflowDir={}", workflowDir);
        log.info("配置信息 - maxConcurrentTasks={}", maxConcurrentTasks);

        cleanIncompleteTasks();
        loadTasksFromDatabase();
        startTaskExecutor();

        log.info("========== TaskService 初始化完成 ==========");
    }

    private void cleanIncompleteTasks() {
        log.info("清理排队中和执行中的任务...");
        transactionTemplate.execute(status -> {
            List<String> statuses = java.util.Arrays.asList("排队中", "执行中");
            taskRepository.deleteByStatusIn(statuses);
            return null;
        });
        log.info("排队中和执行中的任务清理完成");
    }

    @PreDestroy
    public void destroy() {
        log.info("========== TaskService 销毁开始 ==========");
        running = false;
        
        for (Thread workerThread : workerThreads) {
            log.info("停止任务执行工作线程: {}", workerThread.getName());
            workerThread.interrupt();
            try {
                workerThread.join(5000);
                if (workerThread.isAlive()) {
                    log.warn("任务执行工作线程强制停止: {}", workerThread.getName());
                } else {
                    log.info("任务执行工作线程已停止: {}", workerThread.getName());
                }
            } catch (InterruptedException e) {
                log.error("等待线程停止时发生中断", e);
            }
        }
        
        log.info("剩余任务队列大小: {}", taskQueue.size());
        log.info("========== TaskService 销毁完成 ==========");
    }

    private void loadTasksFromDatabase() {
        log.info("开始从数据库加载任务...");
        List<Task> tasks = taskRepository.findAll();

        for (Task task : tasks) {
            taskCache.put(task.getTaskId(), task);
        }
        log.info("从数据库加载任务完成: 总数={}", tasks.size());
    }

    private void startTaskExecutor() {
        log.info("启动任务执行线程...");
        log.info("最大并发任务数: {}", maxConcurrentTasks);

        // 最多可以有3个任务同时执行，比如队列中有十个任务，可以把前三个取出来
        for (int i = 0; i < maxConcurrentTasks; i++) {
            Thread workerThread = new Thread(() -> {
                log.info("任务执行工作线程已启动: {}", Thread.currentThread().getName());
                while (running) {
                    try {
                        Task task = taskQueue.take();
                        log.info("从队列取出任务: taskId={}, type={}, 队列剩余={}",
                                task.getTaskId(), task.getType(), taskQueue.size());
                        executeTask(task);
                    } catch (InterruptedException e) {
                        log.info("任务执行工作线程被中断: {}", Thread.currentThread().getName());
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("任务执行工作线程异常: {}", Thread.currentThread().getName(), e);
                    }
                }
                log.info("任务执行工作线程退出: {}", Thread.currentThread().getName());
            }, "TaskExecutor-" + i);
            workerThreads.add(workerThread);
            workerThread.start();
        }
    }

    public String submitTask(String userId, TaskType type, String description, MultipartFile file,
                             String speaker, String emotion, Integer videoDuration, Boolean isPolish, String fatherTaskId) throws IOException {
        log.info("========== 开始提交任务 ==========");
        log.info("用户ID: {}, 任务类型: {}", userId, type);
        
        // 记录播音员和情绪参数（仅文字生成语音任务）
        if (type == TaskType.TEXT_TO_SPEECH) {
            log.info("播音员: {}, 情绪: {}", speaker, emotion);
        }

        String taskId = UUID.randomUUID().toString();
        log.info("生成任务ID: {}", taskId);

        String filePath = null;
        if (file != null && !file.isEmpty()) {
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".tmp";
            filePath = uploadDir + "/" + taskId + extension;
            File uploadFile = new File(filePath);
            uploadFile.getParentFile().mkdirs();
            file.transferTo(uploadFile);
            log.info("上传文件已保存: {}", filePath);
        }

        // 存入数据库的任务描述只保留前缀
        String fullPrompt = description;
        if (description != null && description.length() > 15) {
            description = description.substring(0, 15);
        }

        Task task = Task.builder()
                .taskId(taskId)
                .userId(userId)
                .type(type)
                .description(description)
                .filePath(filePath)
                .videoDuration(videoDuration)
                .fatherTaskId(fatherTaskId)
                .status("排队中")
                .progress(0)
                .submitTime(LocalDateTime.now())
                .build();

        // 完整提示词存内存，不入库
        task.setPrompt(fullPrompt);
        task.setIsPolish(isPolish != null ? isPolish : true);

        // 播音员和情绪存内存，不入库
        task.setSpeaker(speaker);
        task.setEmotion(emotion);

        taskRepository.save(task);
        // 提交的多媒体生成任务会保存在队列中，每个时刻只能有一个任务在运行
        taskCache.put(taskId, task);
        boolean offered = taskQueue.offer(task);

        log.info("任务已保存到数据库");
        log.info("任务已加入队列: {}", offered ? "成功" : "失败");
        log.info("当前队列大小: {}", taskQueue.size());
        log.info("========== 任务提交完成 ==========");

        return taskId;
    }

    private void executeTask(Task task) {
        log.info("========== 开始执行任务 ==========");
        log.info("任务ID: {}, 类型: {}", task.getTaskId(), task.getType());

        task.setStatus("执行中");
        task.setProgress(0);
        updateTask(task);

        // 执行科普视频生成任务
        if (task.getType() == TaskType.SCIENCE_VIDEO) {
            executeScienceVideoTask(task);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        taskLatches.put(task.getTaskId(), latch);

        try {
            log.info("步骤1: 加载工作流配置");
            String workflowJson = loadWorkflow(task.getType());
            log.info("工作流配置加载成功, 长度: {} 字符", workflowJson.length());

            log.info("步骤2: 填充工作流参数");
            workflowJson = fillWorkflow(workflowJson, task, task.getIsPolish() != null ? task.getIsPolish() : true);
            log.info("工作流参数填充完成");

            log.info("步骤3: 提交到ComfyUI");
            String promptId = submitToComfyUI(workflowJson);
            task.setPromptId(promptId);
            updateTask(task);
            log.info("ComfyUI提交成功, promptId={}", promptId);

            log.info("步骤4: 启动任务监控");
            startTaskMonitor(task);

            log.info("步骤5: 等待ComfyUI任务执行完成...");
            latch.await();
            log.info("任务在ComfyUI中执行完成");

            log.info("========== 任务执行流程完成 ==========");

        } catch (InterruptedException e) {
            log.warn("任务等待被中断: taskId={}", task.getTaskId());
            Thread.currentThread().interrupt();
            task.setStatus("执行失败");
            task.setProgress(0);
            updateTask(task);
            deleteUploadedFile(task);
        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", task.getTaskId(), e);
            task.setStatus("执行失败");
            task.setProgress(0);
            updateTask(task);
            deleteUploadedFile(task);
            log.info("任务执行失败，已清理上传文件");
        } finally {
            taskLatches.remove(task.getTaskId());
        }
    }

    private static final long TASK_TIMEOUT_MS = 60 * 60 * 1000;

    private void startTaskMonitor(Task task) {
        log.info("========== 启动任务监控线程 ==========");
        log.info("任务ID: {}, PromptID: {}", task.getTaskId(), task.getPromptId());
        log.info("轮询间隔: {}ms, 超时时间: {}ms", pollInterval, TASK_TIMEOUT_MS);

        new Thread(() -> {
            int pollCount = 0;
            long startTime = System.currentTimeMillis();
            while (running && "执行中".equals(task.getStatus())) {
                try {
                    long elapsedMs = System.currentTimeMillis() - startTime;
                    if (elapsedMs > TASK_TIMEOUT_MS) {
                        log.error("任务执行超时！任务ID: {}, 已运行: {}ms", task.getTaskId(), elapsedMs);
                        task.setStatus("执行失败");
                        task.setProgress(0);
                        task.setCompleteTime(LocalDateTime.now());
                        updateTask(task);
                        releaseTaskLatch(task.getTaskId());
                        break;
                    }

                    pollCount++;
                    log.info("---------- 第{}次轮询 ----------", pollCount);
                    log.info("任务ID: {}, 当前状态: {}, 已运行: {}ms", task.getTaskId(), task.getStatus(), elapsedMs);

                    checkComfyUIStatus(task);

                    log.info("轮询完成，等待{}ms后继续...", pollInterval);
                    Thread.sleep(pollInterval);
                } catch (InterruptedException e) {
                    log.info("任务监控线程被中断: taskId={}", task.getTaskId());
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("任务监控异常: taskId={}", task.getTaskId(), e);
                }
            }
            log.info("========== 任务监控线程退出 ==========");
            log.info("任务ID: {}, 总轮询次数: {}", task.getTaskId(), pollCount);
        }, "TaskMonitor-" + task.getTaskId()).start();
    }

    private void checkComfyUIStatus(Task task) throws IOException {
        String historyUrl = comfyuiUrl + "/history/" + task.getPromptId();
        log.info("检查ComfyUI状态: {}", historyUrl);

        try {
            log.info("发送HTTP GET请求...");
            String response = httpGet(historyUrl);
            log.info("收到响应，长度: {} 字符", response.length());

            JsonNode jsonNode = objectMapper.readTree(response);

            if (jsonNode.isObject() && jsonNode.size() == 0) {
                log.info("响应为空对象，任务正在执行中");
                return;
            }

            if (jsonNode.has(task.getPromptId())) {
                log.info("找到任务结果，解析状态...");
                JsonNode promptResult = jsonNode.get(task.getPromptId());

                if (promptResult.has("status")) {
                    JsonNode statusNode = promptResult.get("status");
                    String statusStr = statusNode.has("status_str") ? statusNode.get("status_str").asText() : "unknown";
                    log.info("任务状态: {}", statusStr);

                    if ("success".equals(statusStr)) {
                        log.info("任务执行成功！开始提取输出路径...");
                        String outputPath = extractOutputPath(promptResult);

                        if (outputPath != null) {
                            String fullPath = outputDir + "/" + outputPath;
                            log.info("输出文件路径: {}", fullPath);

                            task.setStatus("执行成功");
                            task.setProgress(100);
                            task.setDownloadPath(fullPath);
                            task.setCompleteTime(LocalDateTime.now());
                            updateTask(task);

                            deleteUploadedFile(task);
                            log.info("========== 任务完成 ==========");
                            log.info("任务ID: {}, 输出路径: {}", task.getTaskId(), fullPath);
                        } else {
                            log.warn("未找到输出文件路径");
                        }
                        releaseTaskLatch(task.getTaskId());
                    } else if ("failed".equals(statusStr)) {
                        task.setStatus("执行失败");
                        task.setProgress(0);
                        task.setCompleteTime(LocalDateTime.now());
                        updateTask(task);
                        deleteUploadedFile(task);
                        log.error("========== 任务失败 ==========");
                        log.error("任务ID: {}", task.getTaskId());
                        releaseTaskLatch(task.getTaskId());
                    } else {
                        log.info("任务状态: {}，继续等待...", statusStr);
                    }
                } else {
                    log.info("未找到status字段，任务可能还在执行...");
                }
            } else {
                log.info("未找到任务结果，任务可能还在执行...");
            }
        } catch (Exception e) {
            log.warn("检查ComfyUI状态失败: {}, 错误: {}", task.getTaskId(), e.getMessage());
        }
    }

    private String extractOutputPath(JsonNode promptResult) {
        if (promptResult == null || !promptResult.has("outputs")) {
            return null;
        }
        JsonNode outputs = promptResult.get("outputs");

        String[] outputFields = {"images", "gifs", "videos", "audios", "audio", "files"};

        java.util.Iterator<JsonNode> iterator = outputs.elements();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            for (String field : outputFields) {
                JsonNode fieldNode = node.get(field);
                if (fieldNode != null && fieldNode.isArray() && fieldNode.size() > 0) {
                    JsonNode firstItem = fieldNode.get(0);
                    if (firstItem.has("filename")) {
                        String filename = firstItem.get("filename").asText();
                        String subfolder = firstItem.has("subfolder") ? firstItem.get("subfolder").asText() : "";
                        if (!subfolder.isEmpty()) {
                            return subfolder + "/" + filename;
                        }
                        return filename;
                    }
                }
            }
        }
        log.warn("未找到输出文件: {}", outputs);
        return null;
    }

    private String loadWorkflow(TaskType type) throws IOException {
        String filename = type.name() + ".json";
        String workflowPath = workflowDir + "/" + filename;
        log.debug("加载工作流文件: {}", workflowPath);
        
        org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource(workflowPath);
        if (!resource.exists()) {
            log.error("工作流文件不存在: {}", workflowPath);
            throw new RuntimeException("工作流文件不存在: " + workflowPath);
        }
        
        return objectMapper.readTree(resource.getInputStream()).toString();
    }

    private String fillWorkflow(String workflowJson, Task task, boolean isPolish) throws IOException {
        String result = workflowJson;
        TaskType type = task.getType();

        String description = task.getDescription();
        String prompt = task.getPrompt();
        String promptToUse = (prompt != null && !prompt.isEmpty()) ? prompt : description;
        String fileName = null;
        if (task.getFilePath() != null) {
            fileName = new File(task.getFilePath()).getName();
        }

        log.debug("填充工作流参数: taskId={}, type={}, hasDescription={}, hasPrompt={}, hasFile={}, isPolish={}",
                task.getTaskId(), type, description != null, prompt != null, fileName != null, isPolish);

        switch (type) {
            case TEXT_TO_IMAGE:
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithDeepSeek(promptToUse, "image");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("DeepSeek润色完成(image) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("DeepSeek润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                break;

            case TEXT_TO_VIDEO:
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithDeepSeek(promptToUse, "video");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("DeepSeek润色完成(视频) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("DeepSeek润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                int textVideoDuration = task.getVideoDuration() != null ? task.getVideoDuration() : DEFAULT_VIDEO_DURATION_SHORT;
                int textVideoFrames = textVideoDuration * 24;
                log.info("设置视频时长: {}秒，总帧数: {}", textVideoDuration, textVideoFrames);
                result = result.replace("\"video_frames\"", String.valueOf(textVideoFrames));
                break;

            case IMAGE_TO_VIDEO:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithQwen(promptToUse, task.getFilePath(), "video");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("Qwen3.7-plus润色完成(image_to_video) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("Qwen3.7-plus润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                int imageVideoDuration = task.getVideoDuration() != null ? task.getVideoDuration() : DEFAULT_VIDEO_DURATION_SHORT;
                int imageVideoFrames = imageVideoDuration * 24;
                log.info("设置视频时长: {}秒，总帧数: {}", imageVideoDuration, imageVideoFrames);
                result = result.replace("\"video_frames\"", String.valueOf(imageVideoFrames));
                break;

            case TEXT_TO_VIDEO_AUDIO:
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithDeepSeek(promptToUse, "video_audio");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("DeepSeek润色完成(video_audio) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("DeepSeek润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                int textVideoAudioDuration = task.getVideoDuration() != null ? task.getVideoDuration() : DEFAULT_VIDEO_DURATION_LONG;
                int textVideoAudioFrames = textVideoAudioDuration * 25;
                log.info("设置视频时长: {}秒，总帧数: {}", textVideoAudioDuration, textVideoAudioFrames);
                result = result.replace("\"video_frames\"", String.valueOf(textVideoAudioFrames));
                break;

            case IMAGE_TO_VIDEO_AUDIO:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithQwen(promptToUse, task.getFilePath(), "video_audio");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("Qwen3.7-plus润色完成(image_to_video_audio) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("Qwen3.7-plus润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                int imageVideoAudioDuration = task.getVideoDuration() != null ? task.getVideoDuration() : DEFAULT_VIDEO_DURATION_LONG;
                int imageVideoAudioFrames = imageVideoAudioDuration * 25;
                log.info("设置视频时长: {}秒，总帧数: {}", imageVideoAudioDuration, imageVideoAudioFrames);
                result = result.replace("\"video_frames\"", String.valueOf(imageVideoAudioFrames));
                break;

            case VIDEO_REMOVE_SUBTITLE:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                String maskFileName = task.getTaskId() + "_mask.png";
                String maskFilePath = uploadDir + "/" + maskFileName;
                try {
                    org.springframework.core.io.Resource maskResource = new org.springframework.core.io.ClassPathResource("image/mask.png");
                    if (maskResource.exists()) {
                        java.nio.file.Files.copy(maskResource.getInputStream(), java.nio.file.Paths.get(maskFilePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        log.info("遮罩图片已复制到: {}", maskFilePath);
                    } else {
                        log.warn("遮罩图片不存在: image/mask.png");
                    }
                } catch (IOException e) {
                    log.error("复制遮罩图片失败: {}", e.getMessage());
                }
                result = result.replace("mask image location", maskFileName);
                log.info("已替换遮罩图片路径: {}", maskFileName);
                break;

            case FACE_CONSISTENCY:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = polishPromptWithQwen(promptToUse, task.getFilePath(), "face_consistency");
                        if (polishedPrompt != null && !polishedPrompt.isEmpty()) {
                            log.info("Qwen3.7-plus润色完成(face_consistency) - 原始: {}, 润色后: {}", promptToUse, polishedPrompt);
                            result = result.replace("write describe text here", polishedPrompt);
                        } else {
                            log.warn("Qwen3.7-plus润色失败，使用原始描述");
                            result = result.replace("write describe text here", promptToUse);
                        }
                    } else {
                        log.info("跳过润色，直接使用原始描述");
                        result = result.replace("write describe text here", promptToUse);
                    }
                    log.debug("已替换描述文字");
                }
                break;

            case TEXT_TO_SPEECH:
                if (promptToUse != null) {
                    String speaker = task.getSpeaker() != null ? task.getSpeaker() : "Vivian";
                    String emotion = task.getEmotion() != null ? task.getEmotion() : "平静";
                    // 替换描述文字
                    result = result.replace("write describe text here", promptToUse);
                    log.info("已替换描述文字");
                    // 替换播音员名称
                    result = result.replace("input speaker name here", speaker);
                    log.debug("已替换播音员: {}", speaker);
                    // 替换情绪类型
                    result = result.replace("input emotion type here", emotion);
                    log.debug("已替换情绪: {}", emotion);
                }
                break;

            default:
                break;
        }

        return result;
    }

    /**
     * 使用 LlmService 润色提示词（支持图片/视频）
     * 
     * <p>调用 LlmService 对用户输入的描述进行润色，生成更适合AI生成的提示词。</p>
     * <p>润色过程对用户不可见，仅记录日志。</p>
     * 
     * @param originalPrompt 用户原始输入的描述
     * @param mode 生成模式："image" 或 "video"
     * @return 润色后的提示词，失败时返回 null
     */
    private String polishPromptWithDeepSeek(String originalPrompt, String mode) {
        if (originalPrompt == null || originalPrompt.isEmpty()) {
            return null;
        }

        log.debug("开始调用LlmService润色提示词，模式: {}", mode);

        try {
            String prompt = null;
            if ("video".equals(mode)) {
                // 针对 wan2.2 视频生成模型的润色提示词
                prompt = String.format(
                    "请将以下文字润色成适合AI视频生成的英文提示词，使用wan2.2模型。要求：\n" +
                    "1. 保持原有的核心含义\n" +
                    "2. 添加丰富的场景描述和动态元素\n" +
                    "3. 描述镜头角度和运动方式\n" +
                    "4. 视频要具有电影质感\n" +
                    "5. 运动要平滑自然，符合物理规律\n" +
                    "6. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "7. 遮挡关系在运动过程中保持正确\n" +
                    "8. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "输入文本：\n%s",
                    originalPrompt
                );
            } else if ("video_audio".equals(mode)) {
                // 针对 ltx-2 视频+音频生成模型的润色提示词
                prompt = String.format(
                    "请将以下文字润色成适合AI视频生成的英文提示词，使用ltx-2模型生成带音频的视频。要求：\n" +
                    "1. 保持原有的核心含义\n" +
                    "2. 添加丰富的场景描述和动态元素\n" +
                    "3. 描述镜头角度和运动方式\n" +
                    "4. 视频要具有电影质感\n" +
                    "5. 考虑背景音乐和音效的氛围\n" +
                    "6. 保持提示词适合音频视频同步生成\n" +
                    "7. 运动要平滑自然，符合物理规律\n" +
                    "8. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "9. 遮挡关系在运动过程中保持正确\n" +
                    "10. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "输入文本：\n%s",
                    originalPrompt
                );
            } else if ("image".equals(mode)) {
                // 针对图像生成模型的润色提示词
                prompt = String.format(
                    "请将以下文字润色成适合AI图像生成的英文提示词，保持原有的核心含义，添加丰富的细节描述（如场景、光影、风格、色彩、构图等）：\n%s",
                    originalPrompt
                );
            } else {
                // do nothing
            }
            // 调用deepseek生成提示词
            if(StringUtils.isEmpty(prompt)){
                return originalPrompt;
            }else{
                // 调用 LlmService 生成响应
                String polishedText = llmService.generate(prompt);
                if (polishedText != null && !polishedText.isEmpty()) {
                    // 清理结果，去除多余空格和换行
                    polishedText = polishedText.trim().replaceAll("\\s+", " ");
                    log.info("LlmService润色成功({}) - 原始: '{}', 润色后: '{}'", mode, originalPrompt, polishedText);
                    return polishedText;
                } else {
                    log.warn("LlmService返回空响应");
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("LlmService调用异常({}): {}", mode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 使用 Qwen3.7-plus 多模态模型分析图片并润色提示词
     * 
     * <p>调用 QwenMultiModalService 分析图片内容，并结合用户描述生成适合AI生成的提示词。</p>
     * <p>润色过程对用户不可见，仅记录日志。</p>
     * 
     * @param originalPrompt 用户原始输入的描述
     * @param imagePath 图片文件路径
     * @param mode 生成模式："image" 或 "video"
     * @return 润色后的提示词，失败时返回 null
     */
    private String polishPromptWithQwen(String originalPrompt, String imagePath, String mode) {
        if (originalPrompt == null || originalPrompt.isEmpty()) {
            return null;
        }

        log.debug("开始调用Qwen3.7-plus多模态模型润色提示词，模式: {}, 图片路径: {}", mode, imagePath);

        try {
            // 构建分析提示词
            String analyzePrompt = null;
            if ("video".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片，并结合以下描述生成适合AI视频生成的英文提示词（使用wan2.2模型）。要求：\n" +
                    "1. 描述图片中的主要内容、场景、人物、物体\n" +
                    "2. 添加丰富的动态元素和镜头运动描述\n" +
                    "3. 视频要具有电影质感\n" +
                    "4. 运动要平滑自然，符合物理规律\n" +
                    "5. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "6. 遮挡关系在运动过程中保持正确\n" +
                    "7. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "8. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            } else if ("video_audio".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片，并结合以下描述生成适合AI视频生成的英文提示词（使用ltx-2模型生成带音频的视频）。要求：\n" +
                    "1. 描述图片中的主要内容、场景、人物、物体\n" +
                    "2. 添加丰富的动态元素和镜头运动描述\n" +
                    "3. 视频要具有电影质感\n" +
                    "4. 考虑背景音乐和音效的氛围\n" +
                    "5. 保持提示词适合音频视频同步生成\n" +
                    "6. 运动要平滑自然，符合物理规律\n" +
                    "7. 如有物体运动，需符合惯性、重力等物理规律\n" +
                    "8. 遮挡关系在运动过程中保持正确\n" +
                    "9. 尤其要包括根据分镜的运镜方式和画面描述生成的运动提示词\n" +
                    "10. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            } else if ("face_consistency".equals(mode)) {
                analyzePrompt = String.format(
                    "请分析这张图片中的人物，并结合以下描述生成适合AI人物一致性迁移的英文提示词（使用flux模型）。要求：\n" +
                    "1. 详细描述人物的面部特征（五官、表情、发型）\n" +
                    "2. 描述人物的姿态和动作\n" +
                    "3. 描述人物的着装和配饰\n" +
                    "4. 保持人物特征的一致性\n" +
                    "5. 添加场景描述和光影效果\n" +
                    "6. 保持提示词适合高质量图像生成\n" +
                    "7. 只返回英文提示词，不要任何解释说明、分析或其他文字\n" +
                    "用户描述：%s",
                    originalPrompt
                );
            } else {
            }
            // 调用Qwen大模型生成提示词
            if(StringUtils.isEmpty(analyzePrompt)){
                return originalPrompt;
            }else{
                // 调用 QwenMultiModalService 分析图片
                String response = qwenMultiModalService.analyzeImage(analyzePrompt, imagePath);
                if (response != null && !response.isEmpty()) {
                    // 直接使用返回的文本内容
                    String polishedText = response.trim().replaceAll("\\s+", " ");
                    log.info("Qwen3.7-plus润色成功({}) - 原始: '{}', 润色后: '{}'", mode, originalPrompt, polishedText);
                    return polishedText;
                } else {
                    log.warn("Qwen3.7-plus返回空响应");
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Qwen3.7-plus调用异常({}): {}", mode, e.getMessage(), e);
            return null;
        }
    }

    private String submitToComfyUI(String workflowJson) throws IOException {
        String url = comfyuiUrl + "/prompt";
        log.debug("提交到ComfyUI: {}", url);

        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setEntity(new StringEntity(workflowJson, "UTF-8"));

        try {
            org.apache.http.HttpResponse response = httpClient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

            if (statusCode == 200) {
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                String promptId = jsonNode.get("prompt_id").asText();
                log.debug("ComfyUI返回promptId: {}", promptId);
                return promptId;
            } else {
                log.error("ComfyUI API返回错误: status={}, body={}", statusCode, responseBody);
                throw new IOException("ComfyUI API返回错误: " + statusCode + ", " + responseBody);
            }
        } finally {
            httpPost.releaseConnection();
        }
    }

    private void deleteUploadedFile(Task task) {
        if (task.getFilePath() != null) {
            File file = new File(task.getFilePath());
            if (file.exists()) {
                boolean deleted = file.delete();
                log.debug("删除上传文件: path={}, result={}", task.getFilePath(), deleted);
            }
        }
    }

    private void releaseTaskLatch(String taskId) {
        CountDownLatch latch = taskLatches.get(taskId);
        if (latch != null) {
            latch.countDown();
            log.info("释放任务锁: taskId={}", taskId);
        }
    }

    public void registerTaskLatch(String taskId, CountDownLatch latch) {
        taskLatches.put(taskId, latch);
        log.info("注册任务锁: taskId={}", taskId);
    }

    public Task getTaskEntity(String taskId) {
        return taskCache.get(taskId);
    }

    private void updateTask(Task task) {
        taskRepository.save(task);
        taskCache.put(task.getTaskId(), task);
        log.debug("任务已更新: taskId={}, status={}, progress={}",
                task.getTaskId(), task.getStatus(), task.getProgress());
    }

    public TaskResponse getTask(String taskId) {
        Task task = taskCache.get(taskId);
        if (task == null) {
            log.debug("从数据库查询任务: taskId={}", taskId);
            task = taskRepository.findById(taskId).orElse(null);
        }
        return task != null ? convertToResponse(task) : null;
    }

    public List<TaskResponse> getTasksByUser(String userId) {
        log.debug("查询用户任务列表: userId={}", userId);
        return taskRepository.findByUserIdOrderBySubmitTimeDesc(userId)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * 分页查询用户任务列表
     * 
     * @param userId 用户ID
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @param status 状态筛选（可选，"all"表示全部）
     * @param sort 排序方式（"newest"或"oldest"）
     * @param keyword 搜索关键词（可选）
     * @return 分页响应
     */
    public PageResponse<TaskResponse> getTasksByUserPaged(String userId, int page, int size, 
                                                          String status, String sort, String keyword) {
        log.debug("分页查询用户任务列表: userId={}, page={}, size={}, status={}, sort={}, keyword={}", 
                  userId, page, size, status, sort, keyword);

        // 构建排序
        Sort.Direction direction = "oldest".equals(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sortBy = Sort.by(direction, "submitTime");

        // 构建分页请求
        Pageable pageable = PageRequest.of(page, size, sortBy);

        // 根据条件查询
        Page<Task> taskPage;
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        boolean hasStatusFilter = status != null && !"all".equals(status);

        if (hasStatusFilter && hasKeyword) {
            taskPage = taskRepository.searchByUserIdAndStatusAndKeyword(userId, status, keyword.trim(), pageable);
        } else if (hasStatusFilter) {
            taskPage = taskRepository.findByUserIdAndStatus(userId, status, pageable);
        } else if (hasKeyword) {
            taskPage = taskRepository.searchByUserIdAndKeyword(userId, keyword.trim(), pageable);
        } else {
            taskPage = taskRepository.findByUserId(userId, pageable);
        }

        // 获取统计信息
        PageResponse.Statistics statistics = getStatistics(userId);

        // 构建分页响应
        return PageResponse.<TaskResponse>builder()
                .content(taskPage.getContent().stream()
                        .map(this::convertToResponse)
                        .collect(Collectors.toList()))
                .pageNumber(taskPage.getNumber())
                .pageSize(taskPage.getSize())
                .totalElements(taskPage.getTotalElements())
                .totalPages(taskPage.getTotalPages())
                .first(taskPage.isFirst())
                .last(taskPage.isLast())
                .hasNext(taskPage.hasNext())
                .hasPrevious(taskPage.hasPrevious())
                .statistics(statistics)
                .build();
    }

    /**
     * 获取用户任务统计信息
     * 
     * @param userId 用户ID
     * @return 统计信息
     */
    private PageResponse.Statistics getStatistics(String userId) {
        List<Object[]> counts = taskRepository.countByUserIdGroupByStatus(userId);
        
        long total = 0;
        long success = 0;
        long processing = 0;
        long pending = 0;
        long failed = 0;

        for (Object[] row : counts) {
            String status = (String) row[0];
            long count = (Long) row[1];
            total += count;

            switch (status) {
                case "执行成功":
                    success = count;
                    break;
                case "执行中":
                    processing = count;
                    break;
                case "排队中":
                    pending = count;
                    break;
                case "执行失败":
                    failed = count;
                    break;
            }
        }

        return PageResponse.Statistics.builder()
                .total(total)
                .success(success)
                .processing(processing)
                .pending(pending)
                .failed(failed)
                .build();
    }

    private TaskResponse convertToResponse(Task task) {
        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .promptId(task.getPromptId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .downloadPath(task.getDownloadPath())
                .type(task.getType())
                .description(task.getDescription())
                .fatherTaskId(task.getFatherTaskId())
                .submitTime(task.getSubmitTime())
                .completeTime(task.getCompleteTime())
                .build();
    }

    private String httpGet(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Content-Type", "application/json");

        try {
            org.apache.http.HttpResponse response = httpClient.execute(httpGet);
            return EntityUtils.toString(response.getEntity(), "UTF-8");
        } finally {
            httpGet.releaseConnection();
        }
    }

    // 执行科普视频生成任务
    private void executeScienceVideoTask(Task task) {
        String fatherTaskId = task.getTaskId();
        String narration = task.getPrompt() != null ? task.getPrompt() : task.getDescription();
        String userId = task.getUserId();

        log.info("========== 开始执行科普视频生成任务 ==========");
        log.info("父任务ID: {}, 用户ID: {}", fatherTaskId, userId);

        try {
            log.info("步骤1: 场景设计");
            com.example.aiworkshop.dto.response.SceneDesignResponse sceneResponse = videoGenerateTools.sceneDesign(narration);
            log.info("场景设计完成，场景数: {}", sceneResponse != null && sceneResponse.getScenes() != null ? sceneResponse.getScenes().size() : 0);

            log.info("步骤2: 分镜设计");
            com.example.aiworkshop.dto.response.StoryboardDesignResponse storyboardResponse = videoGenerateTools.storyboardDesign(narration, sceneResponse);
            log.info("分镜设计完成");

            log.info("步骤3: 解说音频提示词生成");
            com.example.aiworkshop.dto.response.NarrationAudioResponse narrationAudioResponse = videoGenerateTools.narrationAudioDesign(narration, storyboardResponse);
            log.info("解说音频提示词生成完成");

            java.util.List<String> videoPaths = new java.util.ArrayList<>();

            if (storyboardResponse != null && storyboardResponse.getStoryboard() != null) {
                int totalScenes = storyboardResponse.getStoryboard().size();
                int processedScenes = 0;
                // 对每一个场景进行遍历
                for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.StoryboardScene scene : storyboardResponse.getStoryboard()) {
                    processedScenes++;
                    log.info("========== 处理场景 {}/{} ==========", processedScenes, totalScenes);
                    if (scene.getShots() != null) {
                        // 对每一个镜头进行遍历
                        for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.Shot shot : scene.getShots()) {
                            log.info("处理分镜: sceneId={}, shotId={}", scene.getSceneId(), shot.getShotId());

                            String imagePath = null;
                            com.example.aiworkshop.dto.response.KeyframeDesignResponse keyframeResponse = null;
                            int retryCount = 0;
                            final int maxRetry = 5;

                            String bestImagePath = null;
                            Integer bestScore = null;
                            com.example.aiworkshop.dto.response.ImageQualityDetectionResponse bestQualityResponse = null;

                            // 关键帧图片生成
                            while (retryCount < maxRetry) {
                                if (retryCount == 0) {
                                    // 根据镜头信息首次生成关键帧图片提示词
                                    log.info("步骤5: 分镜图片提示词生成");
                                    keyframeResponse = videoGenerateTools.keyframeDesign(narration, scene.getSceneId(), shot);
                                } else {
                                    log.info("步骤5: 使用改进建议更新提示词（重试第 {}/{} 次）", retryCount, maxRetry);
                                }

                                log.info("步骤6: 生成关键帧图片");
                                String currentImagePath = multimediaUtils.generateImage(keyframeResponse, userId, fatherTaskId);
                                log.info("关键帧图片生成成功: {}", currentImagePath);

                                log.info("步骤7: 图片质量检测");
                                com.example.aiworkshop.dto.response.ImageQualityDetectionResponse imageQuality = videoGenerateTools.imageQualityDetection(keyframeResponse, currentImagePath);
                                
                                Integer currentScore = imageQuality.getTotalScore();
                                log.info("图片质量检测得分: {}", currentScore);

                                if (bestScore == null || (currentScore != null && currentScore > bestScore)) {
                                    bestScore = currentScore;
                                    bestImagePath = currentImagePath;
                                    bestQualityResponse = imageQuality;
                                }
                                
                                if (imageQuality.getPass() != null && imageQuality.getPass()) {
                                    log.info("图片质量检测通过");
                                    imagePath = currentImagePath;
                                    break;
                                } else {
                                    log.warn("图片质量检测未通过，重试第 {}/{} 次", retryCount + 1, maxRetry);
                                    // 如果图片质量档次较低，需要对图片提示词进行动态修改微调
                                    if (imageQuality.getImprovementSuggestions() != null) {
                                        com.example.aiworkshop.dto.response.KeyframeDesignResponse newKeyframeResponse = new com.example.aiworkshop.dto.response.KeyframeDesignResponse();
                                        newKeyframeResponse.setChinese(imageQuality.getImprovementSuggestions().getChinese());
                                        newKeyframeResponse.setEnglish(imageQuality.getImprovementSuggestions().getEnglish());
                                        keyframeResponse = newKeyframeResponse;
                                        log.info("已使用改进建议更新提示词");
                                    }
                                    
                                    retryCount++;
                                }
                            }

                            if (retryCount >= maxRetry) {
                                log.warn("图片质量检测达到最大重试次数，使用得分最高的图片，得分: {}", bestScore);
                                imagePath = bestImagePath;
                                if (imagePath == null) {
                                    throw new RuntimeException("图片生成失败，场景: " + scene.getSceneId() + ", 分镜: " + shot.getShotId());
                                }
                            }

                            String videoWithAudioPath = null;
                            retryCount = 0;

                            String bestVideoPath = null;
                            Integer bestVideoScore = null;
                            com.example.aiworkshop.dto.response.VideoDesignResponse videoDesignResponse = null;

                            // 视频生成
                            while (retryCount < maxRetry) {
                                if (retryCount == 0) {
                                    // 根据关键帧提示词、镜头信息、图片信息首次生成视频生成提示词
                                    log.info("步骤8: 视频提示词生成");
                                    videoDesignResponse = videoGenerateTools.videoDesign(keyframeResponse, scene.getSceneId(), shot, imagePath);
                                } else {
                                    log.info("步骤8: 使用改进建议更新提示词（重试第 {}/{} 次）", retryCount, maxRetry);
                                }

                                log.info("步骤9: 关键帧生成视频");
                                String currentVideoPath = multimediaUtils.generateVideoWithAudio(videoDesignResponse, imagePath, userId, fatherTaskId);
                                log.info("视频生成成功: {}", currentVideoPath);

                                log.info("步骤10: 视频质量检测");
                                com.example.aiworkshop.dto.response.VideoQualityDetectionResponse videoQuality = videoGenerateTools.videoQualityDetection(videoDesignResponse, currentVideoPath);

                                Integer currentScore = videoQuality.getTotalScore();
                                log.info("视频质量检测得分: {}", currentScore);

                                if (bestVideoScore == null || (currentScore != null && currentScore > bestVideoScore)) {
                                    bestVideoScore = currentScore;
                                    bestVideoPath = currentVideoPath;
                                }

                                if (videoQuality.getPass() != null && videoQuality.getPass()) {
                                    log.info("视频质量检测通过");
                                    videoWithAudioPath = currentVideoPath;
                                    break;
                                } else {
                                    log.warn("视频质量检测未通过，重试第 {}/{} 次", retryCount + 1, maxRetry);
                                    // 如果视频质量档次较低，需要对视频提示词进行动态修改微调
                                    if (videoQuality.getImprovementSuggestions() != null) {
                                        com.example.aiworkshop.dto.response.VideoDesignResponse newVideoDesignResponse = new com.example.aiworkshop.dto.response.VideoDesignResponse();
                                        newVideoDesignResponse.setChinese(videoQuality.getImprovementSuggestions().getChinese());
                                        newVideoDesignResponse.setEnglish(videoQuality.getImprovementSuggestions().getEnglish());
                                        newVideoDesignResponse.setEstimatedDuration(shot.getEstimatedDuration());
                                        videoDesignResponse = newVideoDesignResponse;
                                        log.info("已使用视频改进建议更新提示词");
                                    }

                                    retryCount++;
                                }
                            }

                            if (retryCount >= maxRetry) {
                                log.warn("视频质量检测达到最大重试次数，使用得分最高的视频，得分: {}", bestVideoScore);
                                videoWithAudioPath = bestVideoPath;
                                if (videoWithAudioPath == null) {
                                    throw new RuntimeException("视频生成失败，场景: " + scene.getSceneId() + ", 分镜: " + shot.getShotId());
                                }
                            }

                            log.info("步骤11: 视频背景音去除");
                            String videoWithoutAudioPath = multimediaUtils.removeAudio(videoWithAudioPath);
                            log.info("视频背景音去除完成: {}", videoWithoutAudioPath);

                            String audioPath = null;
                            com.example.aiworkshop.dto.response.NarrationAudioResponse.NarrationItem narrationItem = 
                                narrationAudioResponse != null ? narrationAudioResponse.findBySceneIdAndShotId(scene.getSceneId(), shot.getShotId()) : null;
                            if (narrationItem != null) {
                                log.info("步骤12: 生成镜头对应的音频");
                                audioPath = multimediaUtils.generateSpeech(narrationItem, userId, fatherTaskId);
                                log.info("音频生成成功: {}", audioPath);
                            }

                            log.info("步骤13: 音视频整合");
                            String mergedVideoPath = multimediaUtils.mergeAudioVideo(videoWithoutAudioPath, audioPath);
                            log.info("音视频整合完成: {}", mergedVideoPath);

                            videoPaths.add(mergedVideoPath);
                        }
                    }

                    task.setProgress((int) ((processedScenes * 100.0) / totalScenes));
                    updateTask(task);
                }
            }

            log.info("步骤15: 视频拼接");
            String finalVideoPath = multimediaUtils.concatVideos(videoPaths.toArray(new String[0]));
            log.info("视频拼接完成: {}", finalVideoPath);

            log.info("步骤16: 字幕生成");
            String subtitlePath = multimediaUtils.generateSubtitles(finalVideoPath);
            log.info("字幕生成完成: {}", subtitlePath);

            log.info("步骤17: 字幕烧录");
            String finalVideoWithSubtitles = multimediaUtils.burnSubtitles(finalVideoPath, subtitlePath);
            log.info("字幕烧录完成: {}", finalVideoWithSubtitles);

            task.setStatus("执行成功");
            task.setProgress(100);
            task.setDownloadPath(finalVideoWithSubtitles);
            task.setCompleteTime(LocalDateTime.now());
            updateTask(task);

            log.info("========== 科普视频生成任务完成 ==========");
            log.info("最终视频路径: {}", finalVideoWithSubtitles);

        } catch (Exception e) {
            log.error("科普视频生成任务失败: taskId={}", fatherTaskId, e);
            task.setStatus("执行失败");
            task.setProgress(0);
            task.setCompleteTime(LocalDateTime.now());
            updateTask(task);
        }
    }
}