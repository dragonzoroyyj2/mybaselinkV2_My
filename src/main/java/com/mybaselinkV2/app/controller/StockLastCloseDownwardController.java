package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockLastCloseDownwardService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * ===============================================================
 * 📉 StockLastCloseDownwardController (v2.3 - 실전 안정판)
 * ---------------------------------------------------------------
 * ✅ SSE / start / cancel / chart 완전 통합
 * ✅ StockBatchBoard 구조 동일
 * ✅ JWT 인증 기반 사용자 구분 및 선점 처리
 * ===============================================================
 */
@RestController
@RequestMapping("/api/stock/lastCloseDownward")
public class StockLastCloseDownwardController {

    private static final Logger log = LoggerFactory.getLogger(StockLastCloseDownwardController.class);
    private final StockLastCloseDownwardService service;
    private final TaskStatusService taskStatusService;

    public StockLastCloseDownwardController(StockLastCloseDownwardService service,
                                            TaskStatusService taskStatusService) {
        this.service = service;
        this.taskStatusService = taskStatusService;
    }


    /** ✅ 분석 시작 */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestParam String startDate,
                                   @RequestParam String endDate,
                                   @RequestParam(defaultValue = "100") int topN,
                                   Authentication auth) {
        String user = (auth != null ? auth.getName() : "anonymous");
        String taskId = UUID.randomUUID().toString();
        log.info("📉 [{}] 연속 하락 종목 분석 요청 by {}", taskId, user);

        try {
            if (service.isLocked() && !user.equals(service.getCurrentRunner())) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "다른 사용자가 분석 중입니다.",
                        "runner", service.getCurrentRunner(),
                        "active", true
                ));
            }
            service.startAnalysis(taskId, startDate, endDate, topN);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId, "runner", user));
        } catch (Exception e) {
            log.error("분석 시작 실패", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** ✅ 취소 */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(Authentication auth) {
        String user = (auth != null ? auth.getName() : "anonymous");
        log.warn("⏹ [{}] {}님 취소 요청", service.getCurrentTaskId(), user);
        service.cancelTask(service.getCurrentTaskId(), user);
        return ResponseEntity.ok(Map.of("status", "CANCEL_REQUESTED", "currentUser", user));
    }

    /** ✅ 차트 요청 */
    @GetMapping("/chart/{symbol}")
    public ResponseEntity<Map<String, Object>> chart(@PathVariable String symbol,
                                                     @RequestParam String startDate,
                                                     @RequestParam String endDate) {
        log.info("📈 차트 요청: {} ({} ~ {})", symbol, startDate, endDate);
        return ResponseEntity.ok(service.generateChart(symbol, startDate, endDate));
    }
}
