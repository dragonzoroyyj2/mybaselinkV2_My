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
 * 📊 StockBatchAthenaAiController (v4.1 - analyze + chart 완전체)
 * ---------------------------------------------------------------
 * ✅ chart: 즉시 JSON 반환 / 락 X
 * ✅ analyze: 전역락 사용 + SSE 동기화
 * ✅ GProd와 동일한 잔류 락 자동정리(forceUnlockIfNoProcess) 적용
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

    // ===============================================================
    // ✅ chart 모드 (락 없음 / 즉시 JSON)
    // ===============================================================
    @GetMapping("/chart")
    public ResponseEntity<?> chart(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "20,50,200") String maPeriods,
            @RequestParam(defaultValue = "250") int chartPeriod
    ) {
        try {
            log.info("📈 Chart 요청: symbol={}, ma={}, period={}", symbol, maPeriods, chartPeriod);

            Map<String, Object> json = athenaService.runChartMode(symbol, maPeriods, chartPeriod);

            return ResponseEntity.ok(json);

        } catch (Exception e) {
            log.error("❌ Chart 요청 실패: {}", e.getMessage());
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Chart 모드 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    // ===============================================================
    // ✅ analyze 시작
    // ===============================================================
 // ===============================================================
    // 🚀 analyze 시작 (GProd와 동일하게 force 옵션 추가)
    // ===============================================================
    @PostMapping("/start")
    public ResponseEntity<?> start(
            Authentication auth,
            @RequestParam(defaultValue = "ma") String pattern,
            @RequestParam(defaultValue = "8") int workers,
            @RequestParam(defaultValue = "20,50,200") String maPeriods,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "") String symbol,
            @RequestParam(defaultValue = "false") boolean force  // 🔥 형님, 여기 force 추가했습니다!
    ) {
        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        String taskId = UUID.randomUUID().toString();

        log.info("🟢 [{}] AthenaAI 실행 요청 by {} (force={}, pattern={}, workers={}, maPeriods={}, topN={}, symbol={})",
                taskId, username, force, pattern, workers, maPeriods, topN, symbol);

        // ✅ 1. 잔류 락 자동정리
        try {
            globalStockService.forceUnlockIfNoProcess();
        } catch (Exception e) {
            log.warn("⚠️ 잔류 락 자동정리 실패 (무시): {}", e.getMessage());
        }

        // ✅ 2. 전역 락 확인
        if (globalStockService.isLocked()) {
            String runner = globalStockService.getCurrentTaskInfo()
                    .map(i -> i.user)
                    .orElse("다른 사용자");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", runner + "님이 이미 실행 중입니다.");
            return ResponseEntity.status(409).body(body);
        }

        try {
            // ✅ 서비스 호출 시 force 파라미터 전달 (Service 쪽에도 boolean force 인자 추가 필요)
            athenaService.startUpdate(
                    taskId, 
                    pattern, 
                    maPeriods, 
                    workers, 
                    topN, 
                    symbol, 
                    username,
                    force // 🔥 서비스로 force 전달
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("runner", username);
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("⚠️ [{}] AthenaAI 실행 예외", taskId, e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }


    // ===============================================================
    // ❌ 취소
    // ===============================================================
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(Authentication auth, @PathVariable String taskId) {

        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        log.warn("🟥 [{}] AthenaAI 취소 요청 by {}", taskId, username);

        try {
            boolean cancelled = athenaService.cancelTask(taskId, username);

            if (!cancelled) {
                LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                String runner = athenaService.getCurrentRunner() != null
                        ? athenaService.getCurrentRunner() : "IDLE";

                body.put("error", "취소 실패: 현재 실행자(" + runner + ")가 아님");
                return ResponseEntity.status(409).body(body);
            }

            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", true);
            body.put("taskId", taskId);
            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("❌ [{}] AthenaAI 취소 오류", taskId, e);
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", false);
            body.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    // ===============================================================
    // 🔍 active 조회
    // ===============================================================
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
            if (result.get("progress") instanceof Number n) {
                progress = n.doubleValue();
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
