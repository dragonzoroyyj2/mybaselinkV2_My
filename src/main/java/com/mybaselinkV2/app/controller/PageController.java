package com.mybaselinkV2.app.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 📌 PageController
 * 
 * - 로그인 페이지, 루트 리다이렉트, Dynamic 공통 페이지를 한 곳에서 처리
 * - Dynamic 방식으로 /pages/** 하위 모든 페이지 처리 가능
 * - URL 구조가 바뀌어도 컨트롤러 수정 최소화
 */
@Controller
public class PageController {

    // ================================
    // 1️⃣ 로그인 페이지
    // ================================
    /**
     * 로그인 페이지 요청 처리
     * URL: /login
     * 뷰: templates/login.html
     */

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("pageTitle", "로그인");
        return "login"; // => src/main/resources/templates/login.html
    }

    /**
     * 루트 URL("/") 요청 시 로그인 페이지로 리다이렉트
     */
    @GetMapping("/")
    public String redirectRoot() {
        return "redirect:/login";
    }

    // ================================
    // 2️⃣ 고정 패턴 방식 (선택 사항)
    // ================================
    /**
     * 만약 URL 구조가 항상 /pages/{module}/{sub}/{page} 형태라면
     * 아래 고정 패턴 방식을 사용할 수 있음
     * 
     * 장점:
     * - URL 구조 강제
     * - 잘못된 URL 접근 시 즉시 404 처리
     * 
     * 단점:
     * - URL 깊이 변경 시 컨트롤러 수정 필요
     */
    /*
    @GetMapping("/pages/{module}/{sub}/{page}")
    public String commonPage(
            @PathVariable String module,
            @PathVariable String sub,
            @PathVariable String page
    ) {
        // templates/pages/module/sub/page.html 경로 반환
        return String.format("pages/%s/%s/%s", module, sub, page);
    }
    */

    // ================================
    // 3️⃣ Dynamic 공통 페이지 컨트롤러
    // ================================
    /**
     * URL 패턴: /pages/**
     * - /pages 하위 모든 경로를 처리
     * - URL 깊이에 상관없이 페이지 렌더링 가능
     * - 존재하지 않는 페이지 요청 시 Thymeleaf 기본 404 처리 가능
     * 
     * 동작 원리:
     * 1. HttpServletRequest로 요청 URI를 가져옴
     * 2. 맨 앞 '/' 제거 후 templatePath 생성
     * 3. model에 requestedPath 전달 (404 페이지에서 활용 가능)
     * 4. templatePath 반환 → Thymeleaf가 해당 경로의 HTML 렌더링
     */
    @GetMapping("/pages/**")
    public String commonPage(HttpServletRequest request, Model model) {
        // 요청 URI 예: /pages/p01/p01a04/p01a04List
        String path = request.getRequestURI();

        // 앞의 '/' 제거: pages/p01/p01a04/p01a04List
        String templatePath = path.substring(1);

        // 뷰 이름 전달, 404 페이지에서 활용 가능
        model.addAttribute("requestedPath", templatePath);

        // 반환: templates/pages/... 경로로 렌더링
        return templatePath;
    }
}