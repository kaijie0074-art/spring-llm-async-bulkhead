package com.resumeai.stability.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对照组：与 AI 无关的普通业务接口（登录），固定 ~20ms。 */
@RestController
public class LoginController {

    @GetMapping("/api/login")
    public String login() throws InterruptedException {
        Thread.sleep(20); // 查库+会话签发的正常耗时
        return "{\"code\":0,\"msg\":\"ok\"}";
    }
}
