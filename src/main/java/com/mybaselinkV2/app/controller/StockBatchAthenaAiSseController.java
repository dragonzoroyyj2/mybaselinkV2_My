package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchAthenaAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ===============================================================
 * 📡 StockBatchAthenaAiSseController (v3.6 실전 완전판)
 * ---------------------------------------------------------------
 * ✅ /api/stock/batch/athena/sse
 * ✅ SSE 기반 실시간 로그/진행률 전송
 * ===============================================================
 */
@RestController
public class StockBatchAthenaAiSseController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchAthenaAiSseController.class);
    private final StockBatchAthenaAiService athenaService;

    public StockBatchAthenaAiSseController(StockBatchAthenaAiService athenaService) {
        this.athenaService = athenaService;
    }

    @GetMapping(value = "/api/stock/batch/athena/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = (auth != null) ? auth.getName() : "anonymous";
        log.info("🌐 SSE 연결 요청 (ATHENA) by {}", user);
        return athenaService.createEmitter(user);
    }
}
