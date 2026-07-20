
/**
 * 任务数据访问层接口
 * 
 * <p>提供对任务表的数据库操作方法。</p>
 * <p>继承自JpaRepository，自动获得CRUD操作。</p>
 */
package com.example.aiworkshop.repository;

import com.example.aiworkshop.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * 根据用户ID分页查询任务列表
     * 
     * @param userId 用户ID
     * @param pageable 分页参数
     * @return 分页任务列表
     */
    Page<Task> findByUserId(String userId, Pageable pageable);

    /**
     * 根据用户ID和状态分页查询任务列表
     * 
     * @param userId 用户ID
     * @param status 任务状态
     * @param pageable 分页参数
     * @return 分页任务列表
     */
    Page<Task> findByUserIdAndStatus(String userId, String status, Pageable pageable);

    /**
     * 根据用户ID和关键词搜索任务（工具名称或描述）
     * 
     * @param userId 用户ID
     * @param keyword 搜索关键词
     * @param pageable 分页参数
     * @return 分页任务列表
     */
    @Query("SELECT t FROM Task t WHERE t.userId = :userId AND " +
           "(LOWER(t.type) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Task> searchByUserIdAndKeyword(@Param("userId") String userId, 
                                        @Param("keyword") String keyword, 
                                        Pageable pageable);

    /**
     * 根据用户ID、状态和关键词搜索任务
     * 
     * @param userId 用户ID
     * @param status 任务状态
     * @param keyword 搜索关键词
     * @param pageable 分页参数
     * @return 分页任务列表
     */
    @Query("SELECT t FROM Task t WHERE t.userId = :userId AND t.status = :status AND " +
           "(LOWER(t.type) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Task> searchByUserIdAndStatusAndKeyword(@Param("userId") String userId, 
                                                  @Param("status") String status, 
                                                  @Param("keyword") String keyword, 
                                                  Pageable pageable);

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

    /**
     * 统计用户各状态任务数量
     * 
     * @param userId 用户ID
     * @return 统计结果列表（状态, 数量）
     */
    @Query("SELECT t.status, COUNT(t) FROM Task t WHERE t.userId = :userId GROUP BY t.status")
    List<Object[]> countByUserIdGroupByStatus(@Param("userId") String userId);

    /**
     * 查询指定时间之前已完成（执行成功或执行失败）的任务
     * 
     * <p>用于定时清理24小时前的已完成任务。</p>
     * 
     * @param cutoffTime 截止时间
     * @return 过期任务列表
     */
    @Query("SELECT t FROM Task t WHERE t.submitTime < :cutoffTime AND " +
           "(t.status = '执行成功' OR t.status = '执行失败')")
    List<Task> findExpiredCompletedTasks(@Param("cutoffTime") java.time.LocalDateTime cutoffTime);

    /**
     * 根据状态列表删除任务
     * 
     * <p>用于应用重启时清理排队中和执行中的任务。</p>
     * 
     * @param statuses 状态列表
     */
    void deleteByStatusIn(List<String> statuses);
}
