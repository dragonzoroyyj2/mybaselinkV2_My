package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchGProdService;
import com.mybaselinkV2.app.service.GlobalStockService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * ===============================================================
 * 📊 StockBatchGProdController (v1.1 - 실전 안정판)
 * ---------------------------------------------------------------
 * ✅ /api/stock/batch/gprod/**
 * ✅ GlobalStockService 전역락 완전 연동
 * ✅ SSE 실시간 로그/진행률/상태 전송 (활성화)
 * ✅ Python 프로세스 강제 종료 + 전역 상태 자동 갱신
 * ===============================================================
 */
@RestController
@RequestMapping("/api/stock/batch/gprod")
public class StockBatchGProdController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchGProdController.class);

    private final StockBatchGProdService gProdService;
    private final GlobalStockService globalStockService;
    private final TaskStatusService taskStatusService;

    public StockBatchGProdController(StockBatchGProdService gProdService,
                                     GlobalStockService globalStockService,
                                     TaskStatusService taskStatusService) {
        this.gProdService = gProdService;
        this.globalStockService = globalStockService;
        this.taskStatusService = taskStatusService;
    }

    // ===============================================================
    // 🚀 분석 시작
    // ===============================================================
    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth,
                                   @RequestParam(defaultValue = "16") int workers,
                                   @RequestParam(defaultValue = "3") int historyYears,
                                   @RequestParam(defaultValue = "false") boolean force) {

        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        String taskId = UUID.randomUUID().toString();

        log.info("🟢 [{}] 분석 요청 by {} (force={}, workers={}, years={})", taskId, username, force, workers, historyYears);

        if (globalStockService.isLocked()) {
            String runner = globalStockService.getCurrentTaskInfo()
                    .map(i -> i.user)
                    .orElse("다른 사용자");
            return ResponseEntity.status(409).body(Map.of("error", runner + "님이 이미 실행 중입니다."));
        }

        try {
            gProdService.startUpdate(taskId, force, workers, historyYears, username);
            return ResponseEntity.ok(Map.of("taskId", taskId, "runner", username));
        } catch (Exception e) {
            log.error("⚠️ [{}] 실행 중 예외", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ===============================================================
    // ⏹️ 취소
    // ===============================================================
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(Authentication auth, @PathVariable String taskId) {
        String username = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        log.warn("🟥 [{}] 취소 요청 by {}", taskId, username);
        try {
            boolean cancelled = gProdService.cancelTask(taskId, username);
            if (!cancelled) {
                return ResponseEntity.status(409).body(Map.of("error", "취소 실패: 이미 종료된 작업 또는 권한 없음"));
            }
            return ResponseEntity.ok(Map.of("cancelled", true, "taskId", taskId));
        } catch (Exception e) {
            log.error("❌ [{}] 취소 실패", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of("cancelled", false, "error", e.getMessage()));
        }
    }

    // ===============================================================
    // 🔍 현재 상태 복원
    // ===============================================================
    @GetMapping("/active")
    public ResponseEntity<?> active() {
        var info = globalStockService.getCurrentTaskInfo();
        if (info.isEmpty()) {
            return ResponseEntity.ok(Map.of("active", false));
        }

        var i = info.get();
        var snap = taskStatusService.snapshot(i.taskId);

        return ResponseEntity.ok(Map.of(
                "active", true,
                "taskId", i.taskId,
                "runner", i.user,
                "menu", i.menu,
                "progress", (snap != null && snap.get("result") instanceof Map r && r.get("progress") instanceof Number)
                        ? ((Number) ((Map<?, ?>) snap.get("result")).get("progress")).doubleValue()
                        : 0
        ));
    }
}
