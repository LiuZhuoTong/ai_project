package com.example.aiworkshop.entity;

import javax.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tasks", schema = "gulu")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    @Id
    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "prompt_id", length = 100)
    private String promptId;

    @Column(name = "father_task_id", length = 36)
    private String fatherTaskId;

    @Column(name = "user_id", length = 100, nullable = false)
    private String userId;

    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = "排队中";

    @Column(name = "progress")
    @Builder.Default
    private Integer progress = 0;

    @Column(name = "download_path", length = 500)
    private String downloadPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 50, nullable = false)
    private TaskType type;

    @Column(name = "description", length = 50)
    private String description;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Transient
    private Integer videoDuration;

    @Transient
    private Boolean isPolish = true;

    @Transient
    private String prompt;

    @Transient
    private String speaker;

    @Transient
    private String emotion;

    @Column(name = "submit_time", nullable = false)
    private LocalDateTime submitTime;

    @Column(name = "complete_time")
    private LocalDateTime completeTime;
}