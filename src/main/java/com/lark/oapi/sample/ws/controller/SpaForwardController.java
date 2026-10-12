package com.lark.oapi.sample.ws.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端是 React Router（BrowserRouter）单页应用：直接打开 / 刷新 /employees、/e2e 等前端路由时，
 * 服务端没有对应资源会报 Whitelabel 404。这里把前端路由统一 forward 到 index.html，由前端接管。
 * 只匹配无扩展名的一级/二级路径，不影响 /api、/ws 与静态资源。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/e2e", "/hooks", "/supplier", "/employees", "/terminal", "/logs", "/config", "/test",
            "/e2e/**", "/hooks/**", "/supplier/**", "/employees/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
