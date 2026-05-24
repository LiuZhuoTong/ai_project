package com.example.aiworkshop.service;

import com.example.aiworkshop.dto.response.TaskResponse;
import com.example.aiworkshop.entity.Task;
import com.example.aiworkshop.entity.TaskType;
import com.example.aiworkshop.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClients.createDefault();

    @Value("${file.upload-dir:/root/comfyui/storage-user/input}")
    private String uploadDir;

    @Value("${file.output-dir:/root/comfyui/storage-user/output}")
    private String outputDir;

    @Value("${comfyui.url:http://localhost:8188}")
    private String comfyuiUrl;

    @Value("${comfyui.poll-interval:5000}")
    private long pollInterval;

    @Value("${workflow.dir:./workflows}")
    private String workflowDir;

    private final BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    private final Map<String, Task> taskCache = new ConcurrentHashMap<>();
    private Thread taskExecutorThread;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        log.info("========== TaskService 初始化开始 ==========");
        log.info("配置信息 - uploadDir={}", uploadDir);
        log.info("配置信息 - outputDir={}", outputDir);
        log.info("配置信息 - comfyuiUrl={}", comfyuiUrl);
        log.info("配置信息 - pollInterval={}ms", pollInterval);
        log.info("配置信息 - workflowDir={}", workflowDir);

        loadTasksFromDatabase();
        startTaskExecutor();

        log.info("========== TaskService 初始化完成 ==========");
    }

    @PreDestroy
    public void destroy() {
        log.info("========== TaskService 销毁开始 ==========");
        running = false;
        if (taskExecutorThread != null) {
            log.info("停止任务执行线程...");
            taskExecutorThread.interrupt();
            try {
                taskExecutorThread.join(5000);
                if (taskExecutorThread.isAlive()) {
                    log.warn("任务执行线程强制停止");
                } else {
                    log.info("任务执行线程已停止");
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
        int runningCount = 0;

        for (Task task : tasks) {
            taskCache.put(task.getTaskId(), task);
            if ("执行中".equals(task.getStatus())) {
                runningCount++;
                log.info("恢复执行中的任务: taskId={}, type={}", task.getTaskId(), task.getType());
                startTaskMonitor(task);
            }
        }
        log.info("从数据库加载任务完成: 总数={}, 执行中={}", tasks.size(), runningCount);
    }

    private void startTaskExecutor() {
        log.info("启动任务执行线程...");
        taskExecutorThread = new Thread(() -> {
            log.info("任务执行线程已启动");
            while (running) {
                try {
                    Task task = taskQueue.take();
                    log.info("从队列取出任务: taskId={}, type={}, 队列剩余={}",
                            task.getTaskId(), task.getType(), taskQueue.size());
                    executeTask(task);
                } catch (InterruptedException e) {
                    log.info("任务执行线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("任务执行线程异常", e);
                }
            }
            log.info("任务执行线程退出");
        }, "TaskExecutor");
        taskExecutorThread.start();
    }

    public String submitTask(String userId, TaskType type, String description, MultipartFile file) throws IOException {
        log.info("========== 开始提交任务 ==========");
        log.info("用户ID: {}, 任务类型: {}", userId, type);

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
                .status("排队中")
                .progress(0)
                .submitTime(LocalDateTime.now())
                .build();

        taskRepository.save(task);
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

        try {
            log.info("步骤1: 加载工作流配置");
            String workflowJson = loadWorkflow(task.getType());
            log.info("工作流配置加载成功, 长度: {} 字符", workflowJson.length());

            log.info("步骤2: 填充工作流参数");
            workflowJson = fillWorkflow(workflowJson, task);
            log.info("工作流参数填充完成");

            log.info("步骤3: 提交到ComfyUI");
            String promptId = submitToComfyUI(workflowJson);
            task.setPromptId(promptId);
            updateTask(task);
            log.info("ComfyUI提交成功, promptId={}", promptId);

            log.info("步骤4: 启动任务监控");
            startTaskMonitor(task);

            log.info("========== 任务执行流程启动完成 ==========");

        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", task.getTaskId(), e);
            task.setStatus("执行失败");
            task.setProgress(0);
            updateTask(task);
            deleteUploadedFile(task);
            log.info("任务执行失败，已清理上传文件");
        }
    }

    private void startTaskMonitor(Task task) {
        log.info("========== 启动任务监控线程 ==========");
        log.info("任务ID: {}, PromptID: {}", task.getTaskId(), task.getPromptId());
        log.info("轮询间隔: {}ms", pollInterval);
        
        new Thread(() -> {
            int pollCount = 0;
            while (running && "执行中".equals(task.getStatus())) {
                try {
                    pollCount++;
                    log.info("---------- 第{}次轮询 ----------", pollCount);
                    log.info("任务ID: {}, 当前状态: {}", task.getTaskId(), task.getStatus());
                    
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
                    } else if ("failed".equals(statusStr)) {
                        task.setStatus("执行失败");
                        task.setProgress(0);
                        task.setCompleteTime(LocalDateTime.now());
                        updateTask(task);
                        deleteUploadedFile(task);
                        log.error("========== 任务失败 ==========");
                        log.error("任务ID: {}", task.getTaskId());
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
        File file = new File(workflowPath);
        if (!file.exists()) {
            log.error("工作流文件不存在: {}", workflowPath);
            throw new RuntimeException("工作流文件不存在: " + workflowPath);
        }
        log.debug("加载工作流文件: {}", workflowPath);
        return objectMapper.readTree(file).toString();
    }

    private String fillWorkflow(String workflowJson, Task task) throws IOException {
        String result = workflowJson;
        TaskType type = task.getType();

        String description = task.getDescription();
        String fileName = null;
        if (task.getFilePath() != null) {
            fileName = new File(task.getFilePath()).getName();
        }

        log.debug("填充工作流参数: taskId={}, type={}, hasDescription={}, hasFile={}",
                task.getTaskId(), type, description != null, fileName != null);

        switch (type) {
            case TEXT_TO_IMAGE:
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case TEXT_TO_VIDEO:
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case IMAGE_TO_VIDEO:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case TEXT_TO_VIDEO_WITH_AUDIO:
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case IMAGE_TO_VIDEO_WITH_AUDIO:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case VIDEO_REMOVE_SUBTITLE:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                break;

            case FACE_consistency_TRANSFER:
                if (fileName != null) {
                    result = result.replace("upload file here", fileName);
                    log.debug("已替换文件名");
                }
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
                }
                break;

            case TEXT_TO_SPEECH:
                if (description != null) {
                    result = result.replace("write describe text here", description);
                    log.debug("已替换描述文字");
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

    private TaskResponse convertToResponse(Task task) {
        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .promptId(task.getPromptId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .downloadPath(task.getDownloadPath())
                .type(task.getType())
                .description(task.getDescription())
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
}