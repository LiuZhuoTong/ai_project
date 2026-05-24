
/**
 * 任务数据访问层接口
 * 
 * <p>提供对任务表的数据库操作方法。</p>
 * <p>继承自JpaRepository，自动获得CRUD操作。</p>
 */
package com.example.aiworkshop.repository;

import com.example.aiworkshop.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务Repository接口
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, String> {

    /**
     * 根据用户ID查询任务列表，按提交时间降序排列
     * 
     * @param userId 用户ID
     * @return 任务列表
     */
    List<Task> findByUserIdOrderBySubmitTimeDesc(String userId);

    /**
     * 根据状态查询任务列表
     * 
     * @param status 任务状态
     * @return 任务列表
     */
    List<Task> findByStatus(String status);

    /**
     * 根据ComfyUI的promptId查询任务
     * 
     * @param promptId ComfyUI返回的任务ID
     * @return 任务对象
     */
    Task findByPromptId(String promptId);
}
