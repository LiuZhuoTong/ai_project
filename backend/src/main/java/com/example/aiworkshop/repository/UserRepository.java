/**
 * 用户数据访问层接口
 * 
 * <p>提供对用户表的数据库操作方法。</p>
 * <p>继承自JpaRepository，自动获得CRUD操作。</p>
 */
package com.example.aiworkshop.repository;

import com.example.aiworkshop.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户Repository接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根据用户名查询用户
     * 
     * @param username 用户名
     * @return 用户对象（可选）
     */
    Optional<User> findByUsername(String username);

    /**
     * 检查用户名是否存在
     * 
     * @param username 用户名
     * @return 是否存在
     */
    boolean existsByUsername(String username);
}