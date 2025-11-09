package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchGProdService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ===============================================================
 * 📡 StockBatchGProdSseController (v1.0 - SSE 통합 실전판)
 * ---------------------------------------------------------------
 * ✅ /api/stock/batch/gprod/sse
 * ✅ GlobalStock 기반 SSE 스트림 전송
 * ===============================================================
 */
@RestController
public class StockBatchGProdSseController {
	 private static final Logger log = LoggerFactory.getLogger(StockBatchGProdController.class);

    private final StockBatchGProdService gProdService;

    public StockBatchGProdSseController(StockBatchGProdService gProdService) {
        this.gProdService = gProdService;
    }

    @GetMapping(value = "/api/stock/batch/gprod/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = auth != null ? auth.getName() : "anonymous";
        log.info("🌐 SSE 연결 요청: {}", user);
        return gProdService.createEmitter(user);
    }
}
