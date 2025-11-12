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
import java.util.LinkedHashMap;

/**
 * ===============================================================
 * 📊 StockBatchGProdController (v1.2 - 락 자동해제 안정판)
 * ---------------------------------------------------------------
 * ✅ /api/stock/batch/gprod/**
 * ✅ GlobalStockService 전역락 완전 연동
 * ✅ SSE 실시간 로그/진행률/상태 전송 (활성화)
 * ✅ Python 프로세스 강제 종료 + 전역 상태 자동 갱신
 * ✅ 취소 직후 즉시 재시작 가능 (락 해제 지연 방지)
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

        // ✅ 취소 직후 남아있을 수 있는 잠금 상태 정리 (자동 클린업)
        try {
            globalStockService.forceUnlockIfNoProcess(); // 새로 추가 (락 잔존 방지)
        } catch (Exception e) {
            log.warn("⚠️ 잠금 상태 자동 정리 실패 (무시): {}", e.getMessage());
        }

        // ✅ 현재 락 확인
        if (globalStockService.isLocked()) {
            String runner = globalStockService.getCurrentTaskInfo()
                    .map(i -> i.user)
                    .orElse("다른 사용자");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", runner + "님이 이미 실행 중입니다.");
            return ResponseEntity.status(409).body(body);
        }

        try {
            gProdService.startUpdate(taskId, force, workers, historyYears, username);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("runner", username);

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("⚠️ [{}] 실행 중 예외", taskId, e);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(body);
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
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "취소 실패: 이미 종료된 작업 또는 권한 없음");
                return ResponseEntity.status(409).body(body);
            }

            // ✅ 즉시 전역 락 해제 (취소 후 잔류 락 방지)
            globalStockService.unlockForce();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", true);
            body.put("taskId", taskId);

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            log.error("❌ [{}] 취소 실패", taskId, e);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cancelled", false);
            body.put("error", e.getMessage());

            return ResponseEntity.internalServerError().body(body);
        }
    }

    // ===============================================================
    // 🔍 현재 상태 복원
    // ===============================================================
    @GetMapping("/active")
    public ResponseEntity<?> active() {

        var info = globalStockService.getCurrentTaskInfo();
        if (info.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("active", false);
            return ResponseEntity.ok(body);
        }

        var i = info.get();
        var snap = taskStatusService.snapshot(i.taskId);

        double progress = 0;
        if (snap != null && snap.get("result") instanceof Map<?, ?> r && r.get("progress") instanceof Number n) {
            progress = n.doubleValue();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("taskId", i.taskId);
        body.put("runner", i.user);
        body.put("menu", i.menu);
        body.put("progress", progress);

        return ResponseEntity.ok(body);
    }
}
