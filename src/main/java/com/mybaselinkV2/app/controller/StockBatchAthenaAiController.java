package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchAthenaAiService;
import com.mybaselinkV2.app.service.GlobalStockService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ===============================================================
 * 📊 StockBatchAthenaAiController (v3.6 - 실전 완전판)
 * ---------------------------------------------------------------
 * ✅ /api/stock/batch/athena/**
 * ✅ GlobalStockService 락 연동
 * ✅ SSE 기반 진행률/로그/취소 완전 대응
 * ===============================================================
 */
@RestController
@RequestMapping("/api/stock/batch/athena")
public class StockBatchAthenaAiController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchAthenaAiController.class);

    private final StockBatchAthenaAiService athenaService;
    private final GlobalStockService globalStockService;
    private final TaskStatusService taskStatusService;

    public StockBatchAthenaAiController(StockBatchAthenaAiService athenaService,
                                        GlobalStockService globalStockService,
                                        TaskStatusService taskStatusService) {
        this.athenaService = athenaService;
        this.globalStockService = globalStockService;
        this.taskStatusService = taskStatusService;
    }

    // 🚀 분석 시작
    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth,
                                   @RequestParam(defaultValue = "ma") String pattern,
                                   @RequestParam(defaultValue = "8") int workers,
                                   @RequestParam(defaultValue = "5") int years,
                                   @RequestParam(defaultValue = "false") boolean excludeNeg) {

        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        String taskId = UUID.randomUUID().toString();

        log.info("🟢 [{}] AthenaAI 실행 요청 by {} (pattern={}, workers={}, years={}, excludeNeg={})",
                taskId, username, pattern, workers, years, excludeNeg);

        // ✅ 이미 실행 중인지 체크
        if (athenaService.isLocked()) {
            String runner = athenaService.getCurrentRunner();
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("error", runner + "님이 이미 실행 중입니다.");
            return ResponseEntity.status(409).body(body);
        }

        try {
            athenaService.startUpdate(taskId, pattern, excludeNeg, workers, years, username);

            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("runner", username);
            return ResponseEntity.ok(body);

        } catch (IllegalStateException e) {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.status(409).body(body);

        } catch (Exception e) {
            log.error("⚠️ [{}] AthenaAI 실행 중 예외", taskId, e);
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    // ⏹️ 취소
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(Authentication auth, @PathVariable String taskId) {

        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        log.warn("🟥 [{}] AthenaAI 취소 요청 by {}", taskId, username);

        try {
            boolean cancelled = athenaService.cancelTask(taskId, username);

            if (!cancelled) {
                String currentRunner = athenaService.getCurrentRunner() != null
                        ? athenaService.getCurrentRunner() : "IDLE";

                LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                body.put("error", "취소 실패: 현재 실행자(" + currentRunner + ")가 아니거나 이미 종료된 작업입니다.");
                return ResponseEntity.status(409).body(body);
            }

            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", true);
            body.put("taskId", taskId);
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("❌ [{}] AthenaAI 취소 실패", taskId, e);
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", false);
            body.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    // 🔍 현재 상태 조회
    @GetMapping("/active")
    public ResponseEntity<?> active() {

        if (!athenaService.isLocked()) {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("active", false);
            return ResponseEntity.ok(body);
        }

        String taskId = athenaService.getCurrentTaskId();
        String runner = athenaService.getCurrentRunner();
        Map<String, Object> snap = taskStatusService.snapshot(taskId);

        double progress = 0;
        if (snap != null && snap.get("result") instanceof Map result) {
            if (result.get("progress") instanceof Number) {
                progress = ((Number) result.get("progress")).doubleValue();
            }
        }

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("taskId", taskId);
        body.put("runner", runner);
        body.put("menu", "ATHENA");
        body.put("progress", progress);

        return ResponseEntity.ok(body);
    }
}
