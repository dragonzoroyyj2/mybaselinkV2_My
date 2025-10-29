package com.mybaselinkV2.app.controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.mybaselinkV2.app.service.StockBatchService;
import com.mybaselinkV2.app.service.TaskStatusService;

@RestController
@RequestMapping("/api/stock/batch")
public class StockBatchController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchController.class);
    private final StockBatchService stockBatchService;
    private final TaskStatusService taskStatusService;

    public StockBatchController(StockBatchService stockBatchService, TaskStatusService taskStatusService) {
        this.stockBatchService = stockBatchService;
        this.taskStatusService = taskStatusService;
    }

    // 시작
    @PostMapping("/update")
    public ResponseEntity<?> startBatchUpdate(@RequestParam(defaultValue = "8") int workers,
                                              @RequestParam(defaultValue = "false") boolean force,
                                              Principal principal) {
        String requester = (principal != null) ? principal.getName() : "알 수 없음";

        if (stockBatchService.isLocked() && !requester.equals(stockBatchService.getCurrentRunner())) {
            String runner = stockBatchService.getCurrentRunner();
            String tid = stockBatchService.getCurrentTaskId();
            double progress = 0.0;
            if (tid != null) {
                TaskStatusService.TaskStatus ts = taskStatusService.getTaskStatus(tid);
                if (ts != null && ts.getResult() != null) {
                    Object p = ts.getResult().get("progress");
                    if (p instanceof Number) progress = ((Number)p).doubleValue();
                }
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", "다른 사용자가 업데이트 중입니다.",
                           "runner", runner == null ? "알 수 없음" : runner,
                           "progress", progress));
        }

        String taskId = UUID.randomUUID().toString();
        log.info("📊 전체 종목 업데이트 요청: {} by {}", taskId, requester);

        try {
            stockBatchService.startUpdate(taskId, force, workers);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId));
        } catch (IllegalStateException e) {
            log.warn("[{}] 선점 실패: {}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("업데이트 시작 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "시작 실패: " + e.getMessage()));
        }
    }

    // 상태: 특정 taskId
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(stockBatchService.getStatusWithLogs(taskId));
    }

    // 상태: 현재 진행중인 작업
    @GetMapping("/status/current")
    public ResponseEntity<Map<String, Object>> getCurrentStatus() {
        String tid = stockBatchService.getCurrentTaskId();
        return ResponseEntity.ok(stockBatchService.getStatusWithLogs(tid));
    }

    // 취소: 소유자만 가능
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId, Principal principal) {
        String requester = (principal != null) ? principal.getName() : "알 수 없음";
        try {
            stockBatchService.cancelTask(taskId, requester);
            return ResponseEntity.ok(Map.of("status", "CANCELLED"));
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", se.getMessage()));
        }
    }

    // 활성 상태 조회(페이지 진입시)
    @GetMapping("/active")
    public ResponseEntity<?> active() {
        boolean active = stockBatchService.isLocked();
        String tid = stockBatchService.getCurrentTaskId();
        String runner = stockBatchService.getCurrentRunner();
        double progress = 0.0;
        if (tid != null) {
            TaskStatusService.TaskStatus ts = taskStatusService.getTaskStatus(tid);
            if (ts != null && ts.getResult() != null) {
                Object p = ts.getResult().get("progress");
                if (p instanceof Number) progress = ((Number)p).doubleValue();
            }
        }
        return ResponseEntity.ok(Map.of(
                "active", active,
                "taskId", tid,
                "runner", runner == null ? "알 수 없음" : runner,
                "progress", progress
        ));
    }
}
