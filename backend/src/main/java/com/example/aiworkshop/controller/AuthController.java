/**
 * 用户认证控制器
 * 
 * <p>提供用户名密码登录相关的REST API接口：</p>
 * <ul>
 *   <li>用户注册</li>
 *   <li>用户登录</li>
 *   <li>检查登录状态</li>
 * </ul>
 */
package com.example.aiworkshop.controller;

import com.example.aiworkshop.entity.User;
import com.example.aiworkshop.service.UserAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户认证API控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    /** 用户认证服务 */
    private final UserAuthService userAuthService;

    /**
     * 用户注册
     * 
     * <p>前端调用此接口创建新用户账户。</p>
     * 
     * @param request 注册请求，包含username、password、nickname
     * @return 注册结果，成功时返回用户信息
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String username = request.get("username");
            String password = request.get("password");
            String nickname = request.get("nickname");

            // 参数验证
            if (username == null || username.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "用户名不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 只允许特定用户注册
            if (!"liuzhuotong_zh".equals(username)) {
                result.put("success", false);
                result.put("message", "只允许特定用户注册");
                return ResponseEntity.badRequest().body(result);
            }
            
            if (password == null || password.length() < 6) {
                result.put("success", false);
                result.put("message", "密码长度不能少于6位");
                return ResponseEntity.badRequest().body(result);
            }

            // 执行注册
            User user = userAuthService.register(username, password, nickname);
            
            result.put("success", true);
            result.put("message", "注册成功");
            result.put("userId", user.getId());
            result.put("username", user.getUsername());
            result.put("nickname", user.getNickname());
            
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.error("注册失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 用户登录
     * 
     * <p>前端调用此接口进行用户名密码登录。</p>
     * 
     * @param request 登录请求，包含username和password
     * @return 登录结果，成功时返回用户信息
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String username = request.get("username");
            String password = request.get("password");

            // 参数验证
            if (username == null || username.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "用户名不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            if (password == null || password.isEmpty()) {
                result.put("success", false);
                result.put("message", "密码不能为空");
                return ResponseEntity.badRequest().body(result);
            }

            // 执行登录
            User user = userAuthService.login(username, password);
            
            result.put("success", true);
            result.put("message", "登录成功");
            result.put("userId", user.getId());
            result.put("username", user.getUsername());
            result.put("nickname", user.getNickname());
            result.put("userType", user.getUserType());
            result.put("loginCount", user.getLoginCount());
            
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.error("登录失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 检查用户是否存在
     * 
     * <p>前端调用此接口检查用户名是否已被注册。</p>
     * 
     * @param username 用户名
     * @return 检查结果
     */
    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Object>> checkUsername(@RequestParam String username) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean exists = userAuthService.existsByUsername(username);
            result.put("exists", exists);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("检查用户名失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}