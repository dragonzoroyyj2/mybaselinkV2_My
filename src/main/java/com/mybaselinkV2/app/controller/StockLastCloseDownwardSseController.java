package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockLastCloseDownwardService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ===============================================================
 * 📡 SSE Controller for 연속 하락 종목 분석
 * ---------------------------------------------------------------
 * ✅ /api/stock/lastCloseDownward/sse
 * ✅ 실시간 로그, 진행률, 결과 스트림
 * ===============================================================
 */
@RestController
public class StockLastCloseDownwardSseController {

    private final StockLastCloseDownwardService service;

    public StockLastCloseDownwardSseController(StockLastCloseDownwardService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/stock/lastCloseDownward/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = (auth != null) ? auth.getName() : "anonymous";
        return service.createEmitter(user);
    }
}
