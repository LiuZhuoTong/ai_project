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
    private final com.example.aiworkshop.tool.PromptPolishUtils promptPolishUtils;
    
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
        
        String narration = task.getDescription();
        task.setStatus("执行中");
        task.setProgress(0);
        updateTask(task);

        // 科普视频生成任务需要在单独的线程中执行，避免占用唯一的ComfyUI工作线程
        // 父任务在单独线程中编排，子任务提交到队列由工作线程处理
        if (task.getType() == TaskType.SCIENCE_VIDEO) {
            log.info("科普视频生成解说词:" + narration);
            executeScienceVideoTaskAsync(task, narration);   // 核心:图生视频
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

        // 优先查找正式输出文件（type: "output"），避免选择临时文件（type: "temp"）
        String preferredPath = findOutputByType(outputs, outputFields, "output");
        if (preferredPath != null) {
            return preferredPath;
        }

        // 如果没有找到正式输出，再查找临时文件
        String tempPath = findOutputByType(outputs, outputFields, "temp");
        if (tempPath != null) {
            log.warn("未找到正式输出文件，使用临时文件: {}", tempPath);
            return tempPath;
        }

        log.warn("未找到输出文件: {}", outputs);
        return null;
    }
    
    /**
     * 按类型查找输出文件路径
     * <p>从ComfyUI返回的outputs节点中，根据指定的类型（output/temp）查找输出文件路径。</p>
     * 
     * <h3>背景说明：</h3>
     * <p>ComfyUI工作流执行完成后，可能返回多个输出文件：</p>
     * <ul>
     *   <li><b>正式输出文件</b>（type: "output"）：由SaveImage/SaveAudio等节点生成，存储在output目录下的子目录中，不会被自动清理</li>
     *   <li><b>临时输出文件</b>（type: "temp"）：由PreviewImage/PreviewAudio等节点生成，存储在output根目录下，会被ComfyUI自动清理</li>
     * </ul>
     * 
     * <h3>查找逻辑：</h3>
     * <pre>
     * 1. 遍历outputs节点中的所有输出项
     * 2. 对每个输出项，检查指定的字段（images/gifs/videos/audios/audio/files）
     * 3. 如果字段存在且为数组，取第一个元素
     * 4. 检查元素的type字段是否与指定类型匹配
     * 5. 如果匹配，返回完整的文件路径（包含subfolder）
     * </pre>
     * 
     * <h3>使用示例：</h3>
     * <pre>
     * // 优先查找正式输出文件
     * String path = findOutputByType(outputs, outputFields, "output");
     * 
     * // 如果没有正式输出，查找临时文件作为备选
     * if (path == null) {
     *     path = findOutputByType(outputs, outputFields, "temp");
     * }
     * </pre>
     * 
     * @param outputs ComfyUI返回的outputs节点
     * @param outputFields 输出字段列表，按优先级顺序排列：{"images", "gifs", "videos", "audios", "audio", "files"}
     * @param type 类型筛选条件，"output"表示正式输出，"temp"表示临时输出，空字符串表示不筛选
     * @return 文件路径（包含subfolder），如果未找到匹配的文件返回null
     */
    private String findOutputByType(JsonNode outputs, String[] outputFields, String type) {
        // 遍历outputs节点中的所有输出项
        java.util.Iterator<JsonNode> iterator = outputs.elements();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            
            // 按优先级检查各个输出字段
            for (String field : outputFields) {
                JsonNode fieldNode = node.get(field);
                
                // 检查字段是否存在且为非空数组
                if (fieldNode != null && fieldNode.isArray() && fieldNode.size() > 0) {
                    JsonNode firstItem = fieldNode.get(0);
                    
                    // 检查是否包含filename字段（必需）
                    if (firstItem.has("filename")) {
                        // 获取type字段进行类型筛选
                        String itemType = firstItem.has("type") ? firstItem.get("type").asText() : "";
                        
                        // 如果指定了类型筛选条件：
                        // - 如果输出项有type字段且不匹配，跳过
                        // - 如果输出项没有type字段（空字符串），视为正式输出（兼容旧版ComfyUI或没有Preview节点的工作流）
                        if (!type.isEmpty() && !itemType.isEmpty() && !type.equals(itemType)) {
                            continue;
                        }
                        
                        // 拼接完整路径：subfolder + filename
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
        String promptToUse = description;
        String fileName = null;
        if (task.getFilePath() != null) {
            fileName = new File(task.getFilePath()).getName();
        }

        log.debug("填充工作流参数: taskId={}, type={}, hasDescription={}, hasFile={}, isPolish={}",
                task.getTaskId(), type, description != null, fileName != null, isPolish);

        switch (type) {
            case TEXT_TO_IMAGE:
                if (promptToUse != null) {
                    if (isPolish) {
                        String polishedPrompt = promptPolishUtils.polishPromptWithDeepSeek(promptToUse, "image");
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
                        String polishedPrompt = promptPolishUtils.polishPromptWithDeepSeek(promptToUse, "video");
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
                        String polishedPrompt = promptPolishUtils.polishPromptWithQwen(promptToUse, task.getFilePath(), "video");
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
                        String polishedPrompt = promptPolishUtils.polishPromptWithDeepSeek(promptToUse, "video_audio");
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
                        String polishedPrompt = promptPolishUtils.polishPromptWithQwen(promptToUse, task.getFilePath(), "video_audio");
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
                        String polishedPrompt = promptPolishUtils.polishPromptWithQwen(promptToUse, task.getFilePath(), "face_consistency");
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
                    
                    // 标准化speaker名称，解决大小写不匹配问题
                    speaker = normalizeSpeaker(speaker);
                    
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
                                                          String status, String sort, String keyword, boolean onlyParent) {
        log.debug("分页查询用户任务列表: userId={}, page={}, size={}, status={}, sort={}, keyword={}, onlyParent={}", 
                  userId, page, size, status, sort, keyword, onlyParent);

        // 构建排序
        Sort.Direction direction = "oldest".equals(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sortBy = Sort.by(direction, "submitTime");

        // 构建分页请求
        Pageable pageable = PageRequest.of(page, size, sortBy);

        // 根据条件查询
        Page<Task> taskPage;
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        boolean hasStatusFilter = status != null && !"all".equals(status);

        if (onlyParent) {
            // 只查询父任务（fatherTaskId为空）
            if (hasStatusFilter && hasKeyword) {
                taskPage = taskRepository.searchParentByUserIdAndStatusAndKeyword(userId, status, keyword.trim(), pageable);
            } else if (hasStatusFilter) {
                taskPage = taskRepository.findByUserIdAndStatusAndFatherTaskIdIsNull(userId, status, pageable);
            } else if (hasKeyword) {
                taskPage = taskRepository.searchParentByUserIdAndKeyword(userId, keyword.trim(), pageable);
            } else {
                taskPage = taskRepository.findByUserIdAndFatherTaskIdIsNull(userId, pageable);
            }
        } else {
            if (hasStatusFilter && hasKeyword) {
                taskPage = taskRepository.searchByUserIdAndStatusAndKeyword(userId, status, keyword.trim(), pageable);
            } else if (hasStatusFilter) {
                taskPage = taskRepository.findByUserIdAndStatus(userId, status, pageable);
            } else if (hasKeyword) {
                taskPage = taskRepository.searchByUserIdAndKeyword(userId, keyword.trim(), pageable);
            } else {
                taskPage = taskRepository.findByUserId(userId, pageable);
            }
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
     * 批量获取父任务的子任务
     * 
     * @param userId 用户ID
     * @param parentIds 父任务ID列表
     * @return 子任务列表
     */
    public List<TaskResponse> getChildTasksByParentIds(String userId, List<String> parentIds) {
        log.debug("批量获取子任务: userId={}, parentIds={}", userId, parentIds);
        
        List<Task> tasks = taskRepository.findByUserIdAndFatherTaskIdIn(userId, parentIds);
        
        return tasks.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
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

    /**
     * 执行科普视频生成任务
     * <p>完整的科普视频生成流程，包含18个步骤：</p>
     * <pre>
     * 1. 场景设计 - 根据解说词生成多个场景
     * 2. 分镜设计 - 为每个场景生成多个镜头
     * 3. 解说音频提示词生成 - 为每个镜头生成配音文本
     * 4. 遍历场景和镜头
     * 5. 分镜图片提示词生成 - 为每个镜头生成图片提示词
     * 6. 生成关键帧图片 - 调用文生图生成图片
     * 7. 图片质量检测 - AI检测图片质量，不通过则重试（最多5次）
     * 8. 视频提示词生成 - 为每个镜头生成视频提示词
     * 9. 关键帧生成视频 - 调用图生视频生成视频
     * 10. 视频质量检测 - AI检测视频质量，不通过则重试（最多5次）
     * 11. 视频背景音去除 - 使用FFmpeg去除视频中的原始音频
     * 12. 生成镜头对应的音频 - 调用文生语音生成配音
     * 13. 音视频整合 - 将配音与视频合并
     * 14. 循环处理下一个镜头
     * 15. 视频拼接 - 将所有镜头视频拼接成完整视频
     * 16. 字幕生成 - 使用Whisper生成字幕
     * 17. 字幕烧录 - 将字幕硬编码到视频中
     * 18. 完成 - 返回最终视频路径
     * </pre>
     * 
     * @param task 科普视频生成任务实体
     */
    private void executeScienceVideoTask(Task task, String narration) {
        String fatherTaskId = task.getTaskId();
        String userId = task.getUserId();

        log.info("========== 开始执行科普视频生成任务 ==========");
        log.info("父任务ID: {}, 用户ID: {}", fatherTaskId, userId);

        try {
            // ========== 步骤0: 解说词润色 ==========
//            log.info("步骤0: 解说词润色");
//            narration = videoGenerateTools.polishNarration(narration);
//            log.info("解说词润色完成，润色后解说词长度: {} 字符", narration.length());

            // ========== 步骤1: 场景设计 ==========
            log.info("步骤1: 场景设计");
            com.example.aiworkshop.dto.response.SceneDesignResponse sceneResponse = videoGenerateTools.sceneDesign(narration);
            log.info("场景设计完成，场景数: {}", sceneResponse != null && sceneResponse.getScenes() != null ? sceneResponse.getScenes().size() : 0);

            // ========== 步骤2: 分镜设计 ==========
            log.info("步骤2: 分镜设计");
            com.example.aiworkshop.dto.response.StoryboardDesignResponse storyboardResponse = videoGenerateTools.storyboardDesign(sceneResponse);
            log.info("分镜设计完成");

            // ========== 步骤3: 解说音频提示词生成 ==========
            log.info("步骤3: 解说音频提示词生成");
            com.example.aiworkshop.dto.response.NarrationAudioResponse narrationAudioResponse = videoGenerateTools.narrationAudioDesign(storyboardResponse);
            log.info("解说音频提示词生成完成");

            // 统计镜头总数，用于进度计算
            int totalShots = 0;
            if (storyboardResponse != null && storyboardResponse.getStoryboard() != null) {
                // 对场景进行遍历
                for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.StoryboardScene scene : storyboardResponse.getStoryboard()) {
                    if (scene.getShots() != null) {
                        totalShots += scene.getShots().size();
                    }
                }
            }
            log.info("镜头总数: {}", totalShots);

            // key 统一格式: sceneId_shotId
            // ========== 步骤4: 镜头遍历，一次性生成所有关键帧提示词，存入map ==========
            log.info("步骤4: 镜头遍历，一次性生成所有关键帧提示词");
            java.util.Map<String, com.example.aiworkshop.dto.response.KeyframeDesignResponse> keyframePromptMap = new java.util.LinkedHashMap<>();
            if (storyboardResponse != null && storyboardResponse.getStoryboard() != null) {
                // 场景遍历
                for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.StoryboardScene scene : storyboardResponse.getStoryboard()) {
                    if (scene.getShots() != null) {
                        // 镜头遍历
                        for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.Shot shot : scene.getShots()) {
                            String key = scene.getSceneId() + "_" + shot.getShotId();
                            log.info("生成关键帧提示词: {}", key);
                            com.example.aiworkshop.dto.response.KeyframeDesignResponse keyframeResponse = videoGenerateTools.keyframeDesign(scene.getSceneId(), shot);
                            keyframePromptMap.put(key, keyframeResponse);
                        }
                    }
                }
            }
            log.info("关键帧提示词生成完成，共 {} 个", keyframePromptMap.size());

            // ========== 步骤5: 一次性完成所有音频生成，结果存入map（value为存储路径） ==========
            // 音频需先于视频提示词生成，以便用真实音频时长+1秒作为视频时长
            log.info("步骤5: 一次性完成所有音频生成");
            java.util.Map<String, String> audioMap = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> audioDurationMap = new java.util.LinkedHashMap<>();
            if (storyboardResponse != null && storyboardResponse.getStoryboard() != null) {
                int processedAudio = 0;
                // 场景遍历
                for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.StoryboardScene scene : storyboardResponse.getStoryboard()) {
                    if (scene.getShots() != null) {
                        // 镜头遍历
                        for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.Shot shot : scene.getShots()) {
                            String key = scene.getSceneId() + "_" + shot.getShotId();
                            log.info("生成音频: {}", key);
                            com.example.aiworkshop.dto.response.NarrationAudioResponse.NarrationItem narrationItem =
                                narrationAudioResponse != null ? narrationAudioResponse.findBySceneIdAndShotId(scene.getSceneId(), shot.getShotId()) : null;
                            if (narrationItem != null && narrationItem.getText() != null && !narrationItem.getText().isEmpty()) {
                                String audioPath = multimediaUtils.generateSpeech(narrationItem, userId, fatherTaskId);
                                audioMap.put(key, audioPath);
                                // 获取真实音频时长（音频生成后才能确定），用于后续视频时长计算
                                Integer audioDuration = multimediaUtils.getAudioDuration(audioPath);
                                audioDurationMap.put(key, audioDuration);
                                log.info("音频生成成功: {}, 真实时长: {} 秒", audioPath, audioDuration);
                            } else {
                                log.warn("未找到镜头对应的解说音频或音频内容为空，跳过音频生成: {}", key);
                            }
                            processedAudio++;
                            updateStageProgress(task, 15, 30, processedAudio, totalShots);
                        }
                    }
                }
            }
            log.info("音频生成完成，共 {} 个", audioMap.size());

            // ========== 步骤6: 镜头遍历，一次性生成所有视频提示词，存入map ==========
            // 视频时长=真实音频时长+1秒冗余；最后一段视频额外+2秒延时，使台词结束后画面继续延续
            log.info("步骤6: 镜头遍历，一次性生成所有视频提示词");
            java.util.Map<String, com.example.aiworkshop.dto.response.VideoDesignResponse> videoPromptMap = new java.util.LinkedHashMap<>();
            final int lastShotExtraDelay = 2; // 最后一段视频台词结束后的额外延时（秒）
            if (storyboardResponse != null && storyboardResponse.getStoryboard() != null) {
                int shotIndex = 0;
                for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.StoryboardScene scene : storyboardResponse.getStoryboard()) {
                    if (scene.getShots() != null) {
                        for (com.example.aiworkshop.dto.response.StoryboardDesignResponse.Shot shot : scene.getShots()) {
                            shotIndex++;
                            String key = scene.getSceneId() + "_" + shot.getShotId();
                            log.info("生成视频提示词: {}", key);
                            com.example.aiworkshop.dto.response.KeyframeDesignResponse keyframeResponse = keyframePromptMap.get(key);
                            String keyframeDesignPrompt = keyframeResponse != null ? keyframeResponse.getEnglish() : null;
                            // 视频时长=真实音频时长+1秒冗余；无音频时使用分镜预估时长
                            Integer audioDuration = audioDurationMap.get(key);
                            int baseDuration = audioDuration != null ? audioDuration + 1
                                    : (shot.getEstimatedDuration() != null ? shot.getEstimatedDuration() : 5);
                            // 最后一段视频额外加结尾延时，台词结束后画面继续延续
                            boolean isLastShot = (shotIndex == totalShots);
                            int videoDuration = isLastShot ? baseDuration + lastShotExtraDelay : baseDuration;
                            if (isLastShot) {
                                log.info("视频时长: {} 秒（真实音频时长: {} 秒 + 1 秒冗余 + 2 秒结尾延时）", videoDuration, audioDuration);
                            } else {
                                log.info("视频时长: {} 秒（真实音频时长: {} 秒 + 1 秒冗余）", videoDuration, audioDuration);
                            }
                            com.example.aiworkshop.dto.response.VideoDesignResponse videoDesignResponse = videoGenerateTools.videoDesign(scene.getSceneId(), shot, keyframeDesignPrompt, videoDuration);
                            videoDesignResponse.setEstimatedDuration(videoDuration);
                            videoPromptMap.put(key, videoDesignResponse);
                        }
                    }
                }
            }
            log.info("视频提示词生成完成，共 {} 个", videoPromptMap.size());

            // ========== 步骤7: 遍历关键帧提示词map，一次性完成所有关键帧图片生成 ==========
            log.info("步骤7: 一次性完成所有关键帧图片生成");
            java.util.Map<String, String> imageMap = new java.util.LinkedHashMap<>();
            int processedImage = 0;
            for (java.util.Map.Entry<String, com.example.aiworkshop.dto.response.KeyframeDesignResponse> entry : keyframePromptMap.entrySet()) {
                String key = entry.getKey();
                log.info("生成关键帧图片: {}", key);
                String imagePath = multimediaUtils.generateImage(entry.getValue(), userId, fatherTaskId);
                imageMap.put(key, imagePath);
                log.info("关键帧图片生成成功: {}", imagePath);
                processedImage++;
                updateStageProgress(task, 30, 55, processedImage, totalShots);
            }
            log.info("关键帧图片生成完成，共 {} 个", imageMap.size());

            // ========== 步骤8: 遍历视频提示词map和关键帧map，一次性完成所有视频生成 ==========
            // 视频时长已在步骤6基于真实音频时长+1秒写入estimatedDuration
            log.info("步骤8: 一次性完成所有视频生成");
            java.util.Map<String, String> videoMap = new java.util.LinkedHashMap<>();
            int processedVideo = 0;
            for (java.util.Map.Entry<String, com.example.aiworkshop.dto.response.VideoDesignResponse> entry : videoPromptMap.entrySet()) {
                String key = entry.getKey();
                log.info("生成视频: {}", key);
                com.example.aiworkshop.dto.response.VideoDesignResponse videoDesignResponse = entry.getValue();
                String imagePath = imageMap.get(key);
                log.info("视频生成时长: {} 秒", videoDesignResponse.getEstimatedDuration());

                String videoWithAudioPath = multimediaUtils.generateVideoByImage(videoDesignResponse, imagePath, userId, fatherTaskId);
                videoMap.put(key, videoWithAudioPath);
                log.info("视频生成成功: {}", videoWithAudioPath);
                processedVideo++;
                updateStageProgress(task, 55, 85, processedVideo, totalShots);
            }
            log.info("视频生成完成，共 {} 个", videoMap.size());

            // ========== 步骤9-10: 视频背景音去除 + 音视频整合 ==========
            log.info("步骤9-10: 视频背景音去除 + 音视频整合");
            java.util.List<String> videoPaths = new java.util.ArrayList<>();
            int processedMerge = 0;
            for (java.util.Map.Entry<String, String> entry : videoMap.entrySet()) {
                String key = entry.getKey();
                String videoWithAudioPath = entry.getValue();

                log.info("处理镜头 {}: 视频背景音去除", key);
                String videoWithoutAudioPath = multimediaUtils.removeAudio(videoWithAudioPath);
                log.info("视频背景音去除完成: {}", videoWithoutAudioPath);

                log.info("处理镜头 {}: 音视频整合", key);
                String audioPath = audioMap.get(key);
                String mergedVideoPath;
                if (audioPath != null) {
                    // 最后一段视频不截断，保留结尾延时（音频结束后画面继续静音）；其余以较短流为准
                    boolean isLastShot = (processedMerge + 1 == videoMap.size());
                    if (isLastShot) {
                        log.info("最后一段视频，保留结尾延时（不按音频截断）");
                        mergedVideoPath = multimediaUtils.mergeAudioVideo(videoWithoutAudioPath, audioPath, false);
                    } else {
                        mergedVideoPath = multimediaUtils.mergeAudioVideo(videoWithoutAudioPath, audioPath);
                    }
                } else {
                    // 如果没有音频，直接使用去除背景音后的视频
                    log.info("无音频文件，直接使用去除背景音后的视频");
                    mergedVideoPath = videoWithoutAudioPath;
                }
                log.info("音视频整合完成: {}", mergedVideoPath);
                videoPaths.add(mergedVideoPath);
                processedMerge++;
                updateStageProgress(task, 85, 95, processedMerge, totalShots);
            }

            // 检查是否生成了视频片段
            if (videoPaths.isEmpty()) {
                throw new RuntimeException("未生成任何视频片段，请检查分镜设计结果");
            }

            // ========== 步骤11: 视频拼接 ==========
            log.info("步骤11: 视频拼接");
            String finalVideoPath = multimediaUtils.concatVideos(videoPaths.toArray(new String[0]));
            log.info("视频拼接完成: {}", finalVideoPath);

            // ========== 任务完成 ==========
            task.setStatus("执行成功");
            task.setProgress(100);
            task.setDownloadPath(finalVideoPath);
            task.setCompleteTime(LocalDateTime.now());
            updateTask(task);

            log.info("========== 科普视频生成任务完成 ==========");
            log.info("最终视频路径: {}", finalVideoPath);

        } catch (Exception e) {
            log.error("科普视频生成任务失败: taskId={}", fatherTaskId, e);
            task.setStatus("执行失败");
            task.setProgress(0);
            task.setCompleteTime(LocalDateTime.now());
            updateTask(task);
        }
    }

    /**
     * 分阶段更新任务进度
     *
     * <p>根据当前阶段已处理的镜头数与总镜头数，按比例计算并更新任务进度。</p>
     *
     * @param task 任务实体
     * @param stageStart 该阶段起始进度百分比
     * @param stageEnd 该阶段结束进度百分比
     * @param processed 已处理镜头数
     * @param total 镜头总数
     */
    private void updateStageProgress(Task task, int stageStart, int stageEnd, int processed, int total) {
        if (total <= 0) {
            return;
        }
        int progress = (int) (stageStart + (stageEnd - stageStart) * ((double) processed / total));
        task.setProgress(progress);
        updateTask(task);
        log.info("阶段进度: {}%", progress);
    }

    /**
     * 异步执行科普视频生成任务
     * <p>将科普视频生成任务放到单独的线程中执行，避免占用ComfyUI工作线程。</p>
     * <p>这样工作线程可以立即返回，继续处理队列中的子任务（图片生成、视频生成等）。</p>
     * 
     * @param task 科普视频生成任务实体
     */
    private void executeScienceVideoTaskAsync(Task task, String narration) {
        new Thread(() -> {
            log.info("========== 科普视频生成任务已提交到异步线程执行 ==========");
            log.info("父任务ID: {}, 异步线程: {}", task.getTaskId(), Thread.currentThread().getName());
            
            try {
                executeScienceVideoTask(task, narration);
            } catch (Exception e) {
                log.error("科普视频生成异步任务执行异常: taskId={}", task.getTaskId(), e);
                task.setStatus("执行失败");
                task.setProgress(0);
                task.setCompleteTime(LocalDateTime.now());
                updateTask(task);
            }
            
            log.info("========== 科普视频生成异步任务线程退出 ==========");
        }, "ScienceVideoExecutor-" + task.getTaskId()).start();
    }


    /**
     * 标准化播音员名称
     * <p>将大模型返回的speaker名称转换为ComfyUI支持的标准名称，解决大小写不匹配问题。</p>
     * <p>ComfyUI支持的播音员列表: ['Aiden', 'Dylan', 'Eric', 'Ono_anna', 'Ryan', 'Serena', 'Sohee', 'Uncle_fu', 'Vivian']</p>
     * 
     * @param speaker 原始播音员名称
     * @return 标准化后的播音员名称，如果无法匹配则返回默认值 Vivian
     */
    private String normalizeSpeaker(String speaker) {
        if (speaker == null || speaker.isEmpty()) {
            return "Vivian";
        }
        
        // ComfyUI支持的播音员列表（注意大小写）
        java.util.Set<String> validSpeakers = new java.util.HashSet<>(
            java.util.Arrays.asList("Aiden", "Dylan", "Eric", "Ono_anna", "Ryan", "Serena", "Sohee", "Uncle_fu", "Vivian")
        );
        
        // 直接匹配
        if (validSpeakers.contains(speaker)) {
            return speaker;
        }
        
        // 大小写不敏感匹配
        String lowerSpeaker = speaker.toLowerCase();
        for (String valid : validSpeakers) {
            if (valid.toLowerCase().equals(lowerSpeaker)) {
                log.info("已标准化播音员名称: {} -> {}", speaker, valid);
                return valid;
            }
        }
        
        // 模糊匹配（移除下划线、空格等）
        String normalized = speaker.toLowerCase().replace("_", "").replace(" ", "");
        for (String valid : validSpeakers) {
            String validNormalized = valid.toLowerCase().replace("_", "");
            if (validNormalized.equals(normalized)) {
                log.info("已标准化播音员名称: {} -> {}", speaker, valid);
                return valid;
            }
        }
        
        // 无法匹配，返回默认值
        log.warn("无法识别播音员名称: {}，使用默认值 Vivian", speaker);
        return "Vivian";
    }
}