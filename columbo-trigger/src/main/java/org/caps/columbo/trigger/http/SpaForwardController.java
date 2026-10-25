package org.caps.columbo.trigger.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 转发：静态资源目录 /app/（Columbo 前端构建产物）的目录路径转发到 index.html。
 * Spring Boot 的欢迎页机制只对根路径生效，子路径目录需要显式转发。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/app", "/app/"})
    public String appIndex() {
        return "forward:/app/index.html";
    }
}
