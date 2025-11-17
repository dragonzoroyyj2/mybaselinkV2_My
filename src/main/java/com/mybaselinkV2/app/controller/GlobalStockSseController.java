package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.GlobalStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ==============================================================
 * 🌐 GlobalStockSseController (v1.0 - 실전 완성판)
 * --------------------------------------------------------------
 * ✅ /api/global/sse
 *    → 모든 메뉴(GProd, AthenaAI 등)에서 공유되는 전역 상태 SSE
 *    → 글로벌 락 획득/해제, 진행률 발생 시 즉시 브로드캐스트
 * ==============================================================
 */
@RestController
public class GlobalStockSseController {

    private static final Logger log = LoggerFactory.getLogger(GlobalStockSseController.class);

    private final GlobalStockService globalStockService;

    public GlobalStockSseController(GlobalStockService globalStockService) {
        this.globalStockService = globalStockService;
    }

    @GetMapping(value = "/api/global/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = (auth != null ? auth.getName() : "anonymous");
        log.info("🌐 글로벌 SSE 연결 요청 by {}", user);
        return globalStockService.createGlobalEmitter(user);
    }
}
