
/**
 * AI工坊后端服务启动类
 * 
 * <p>这是Spring Boot应用的主入口类，负责启动整个后端服务。</p>
 * <p>服务包含以下核心功能：</p>
 * <ul>
 *   <li>微信扫码登录模块</li>
 *   <li>任务提交与处理模块</li>
 *   <li>任务监控模块</li>
 *   <li>与ComfyUI的集成</li>
 * </ul>
 */
package com.example.aiworkshop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot应用主启动类
 */
@SpringBootApplication
public class AiWorkshopApplication {

    /**
     * 应用入口方法
     * 
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiWorkshopApplication.class, args);
    }
}
