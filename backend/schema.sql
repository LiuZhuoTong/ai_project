-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名（用于登录）',
    password VARCHAR(255) NOT NULL COMMENT '密码（加密存储）',
    nickname VARCHAR(50) COMMENT '用户昵称（可选）',
    register_time DATETIME NOT NULL COMMENT '用户注册时间',
    last_login_time DATETIME NOT NULL COMMENT '用户最后一次登录时间',
    login_count INT NOT NULL COMMENT '用户累计登录次数',
    user_type VARCHAR(20) DEFAULT '普通' COMMENT '用户类型：普通、会员、管理员',
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 任务表
CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(36) PRIMARY KEY COMMENT '任务唯一标识（UUID）',
    prompt_id VARCHAR(100) COMMENT 'ComfyUI返回的任务ID',
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID（关联users表）',
    status VARCHAR(20) NOT NULL DEFAULT '排队中' COMMENT '任务状态：排队中、执行中、执行成功、执行失败',
    progress INT DEFAULT 0 COMMENT '任务完成进度（0-100）',
    download_path VARCHAR(500) COMMENT '生成文件下载路径',
    type VARCHAR(50) NOT NULL COMMENT '任务类型',
    description VARCHAR(2000) COMMENT '用户输入的描述文字',
    file_path VARCHAR(500) COMMENT '用户上传文件的存储路径',
    submit_time DATETIME NOT NULL COMMENT '任务提交时间',
    complete_time DATETIME COMMENT '任务完成时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_submit_time (submit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- 添加外键约束（可选）
-- ALTER TABLE tasks ADD CONSTRAINT fk_tasks_user_id FOREIGN KEY (user_id) REFERENCES users(id);