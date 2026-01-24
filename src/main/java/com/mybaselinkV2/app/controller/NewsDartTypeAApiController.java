package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.NewsDartTypeAService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/newsDartTypeAList")
public class NewsDartTypeAApiController {

    private final NewsDartTypeAService service;

    public NewsDartTypeAApiController(NewsDartTypeAService service) {
        this.service = service;
    }

    /** 🔍 DART 공시 리스트 조회 (5개 인자 버전) */
    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,       // mode 추가
            @RequestParam(defaultValue = "true") boolean pagination  // pagination 추가
    ) {
        // 🚩 서비스의 getList(int, int, String, String, boolean)에 맞춰 5개 다 던집니다!
        return service.getList(page, size, search, mode, pagination);
    }
    
    /** 🚀 수동 수집 (테스트용) */
    @GetMapping("/collect")
    public String collect() {
        service.collectAndSave();
        return "DART 데이터 수집 완료!";
    }
}