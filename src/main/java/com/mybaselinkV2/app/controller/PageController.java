package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.PageService;
import com.mybaselinkV2.app.service.ExchangeRateService; // 추가
import com.mybaselinkV2.app.service.StockIndexService; // ✅ 지수 서비스 추가
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 📌 PageController
 * * - 로그인 페이지, 루트 리다이렉트, Dynamic 공통 페이지를 한 곳에서 처리
 * - Dynamic 방식으로 /pages/** 하위 모든 페이지 처리 가능
 * - URL 구조가 바뀌어도 컨트롤러 수정 최소화
 */
@Controller
public class PageController {

    // ✅ PageService 주입 (페이지 타이틀 및 경로 관리용)
    private final PageService pageService;
    // ✅ ExchangeRateService 주입 (환율 정보 조회용) - 추가
    private final ExchangeRateService exchangeRateService;
    // ✅ StockIndexService 주입 (지수 정보 조회용) - ✅ 추가
    private final StockIndexService stockIndexService;

    public PageController(PageService pageService, ExchangeRateService exchangeRateService, StockIndexService stockIndexService) {
        this.pageService = pageService;
        this.exchangeRateService = exchangeRateService; 
        this.stockIndexService = stockIndexService; // ✅ 추가
    }

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
     * * 장점:
     * - URL 구조 강제
     * - 잘못된 URL 접근 시 즉시 404 처리
     * * 단점:
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
     * * 동작 원리:
     * 1. HttpServletRequest로 요청 URI를 가져옴
     * 2. 맨 앞 '/' 제거 후 templatePath 생성
     * 3. model에 requestedPath 전달 (404 페이지에서 활용 가능)
     * 4. PageService를 통해 타이틀 및 경로 정보를 자동 주입
     * 5. templatePath 반환 → Thymeleaf가 해당 경로의 HTML 렌더링
     */
    @GetMapping("/pages/**")
    public String commonPage(HttpServletRequest request, Model model) {
        // 요청 URI 예: /pages/p01/p01a04/p01a04List
        // getServletPath()는 Context Path를 제외한 순수 경로를 가져오므로 더 안전합니다.
        String path = request.getServletPath();

        // 🔥 [수정] 리턴값 맨 앞에 '/'가 있으면 Thymeleaf 템플릿 엔진이 파일을 찾지 못해 500 에러가 발생할 수 있음
        String templatePath = path;
        if (templatePath.startsWith("/")) {
            templatePath = templatePath.substring(1);
        }

        // 뷰 이름 전달, 404 페이지에서 활용 가능
        model.addAttribute("requestedPath", templatePath);

        // ✅ [추가] 하단 바 환율 정보 조회
        // GlobalControllerAdvice와 별개로, 이 컨트롤러를 거치는 모든 /pages/** 페이지에서 확실히 데이터를 주입합니다.
        try {
            model.addAttribute("exchange", exchangeRateService.getLatest());
        } catch (Exception e) {
            // API 호출 실패 시에도 페이지는 열려야 하므로 null 처리
            model.addAttribute("exchange", null);
        }

        // ✅ [추가] 하단 바 지수 정보 조회
        try {
            // stockbar.html에서 사용할 수 있도록 stockIndices라는 이름으로 전달
            model.addAttribute("stockIndices", stockIndexService.getStockIndices());
        } catch (Exception e) {
            model.addAttribute("stockIndices", null);
        }

        // ✅ PageService를 통해 페이지 타이틀 및 경로 자동 설정
        try {
            String[] meta = pageService.getMeta(templatePath);
            if (meta != null && meta.length >= 2) {
                model.addAttribute("pageTitle", meta[0]);
                model.addAttribute("breadcrumb", meta[1]);
            } else {
                model.addAttribute("pageTitle", "정보 없음");
                model.addAttribute("breadcrumb", "Home > Unknown");
            }
        } catch (Exception e) {
            // 서비스 로직 오류 시 기본값 설정 (500 에러 방지)
            model.addAttribute("pageTitle", "페이지 오류");
            model.addAttribute("breadcrumb", "Home > Error");
        }

        // 반환값 예: "pages/main/base"
        // 실제 파일 위치: src/main/resources/templates/pages/main/base.html
        return templatePath;
    }
}