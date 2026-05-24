/**
 * 用户认证服务
 * 
 * <p>负责处理用户名密码登录的完整流程：</p>
 * <ol>
 *   <li>用户注册（创建新用户）</li>
 *   <li>用户登录（验证用户名密码）</li>
 *   <li>更新登录信息（登录时间、登录次数）</li>
 * </ol>
 * <p>支持密码加密存储和验证。</p>
 */
package com.example.aiworkshop.service;

import com.example.aiworkshop.entity.User;
import com.example.aiworkshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户认证服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAuthService {

    /** 用户数据访问层 */
    private final UserRepository userRepository;

    /** 密码加密器（从Spring容器注入） */
    private final PasswordEncoder passwordEncoder;

    /**
     * 用户注册
     * 
     * <p>创建新用户，密码使用BCrypt加密存储。</p>
     * 
     * @param username 用户名
     * @param password 密码
     * @param nickname 昵称（可选）
     * @return 注册成功的用户对象
     * @throws RuntimeException 如果用户名已存在
     */
    public User register(String username, String password, String nickname) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建新用户
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .nickname(nickname != null ? nickname : username)
                .registerTime(LocalDateTime.now())
                .lastLoginTime(LocalDateTime.now())
                .loginCount(1)
                .userType("普通")
                .build();

        // 保存用户
        User savedUser = userRepository.save(user);
        log.info("新用户注册成功: {}", username);

        return savedUser;
    }

    /**
     * 用户登录
     * 
     * <p>验证用户名和密码，登录成功后更新登录信息。</p>
     * 
     * @param username 用户名
     * @param password 密码
     * @return 登录成功的用户对象
     * @throws RuntimeException 如果用户名不存在或密码错误
     */
    public User login(String username, String password) {
        // 查询用户
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户名不存在"));

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 更新登录信息
        user.setLastLoginTime(LocalDateTime.now());
        user.setLoginCount(user.getLoginCount() + 1);
        userRepository.save(user);

        log.info("用户登录成功: {}, 登录次数: {}", username, user.getLoginCount());

        return user;
    }

    /**
     * 根据用户ID查询用户
     * 
     * @param userId 用户ID
     * @return 用户对象（可选）
     */
    public User findUserById(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }

    /**
     * 检查用户名是否存在
     * 
     * @param username 用户名
     * @return 是否存在
     */
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }
}