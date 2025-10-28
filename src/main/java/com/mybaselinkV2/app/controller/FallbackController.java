package com.mybaselinkV2.app.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FallbackController {

    // 확장자가 없는 경우에만 fallback 동작 (SPA 라우팅 지원용)
    @GetMapping("/{path:[^\\.]*}")
    public String redirect() {
        return "forward:/error";
    }
}
