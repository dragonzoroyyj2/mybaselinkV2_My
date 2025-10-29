package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock/batch")
public class StockBatchController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchController.class);
    private final StockBatchService stockBatchService;

    public StockBatchController(StockBatchService stockBatchService) {
        this.stockBatchService = stockBatchService;
    }

    @PostMapping("/update")
    public ResponseEntity<?> startBatchUpdate(@RequestParam(defaultValue = "8") int workers,
                                              @RequestParam(defaultValue = "false") boolean force,
                                              Authentication auth) {
        String username = (auth != null) ? auth.getName() : "알 수 없음";
        if (stockBatchService.isLocked()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "🚫 " + stockBatchService.getCurrentRunner() + "님 실행 중"));
        }
        String taskId = UUID.randomUUID().toString();
        try {
            stockBatchService.startUpdate(taskId, force, workers);
            log.info("[{}] 업데이트 시작 by {}", taskId, username);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId, "runner", username));
        } catch (Exception e) {
            log.error("업데이트 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(stockBatchService.getStatusWithLogs(taskId));
    }

    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId, Authentication auth) {
        String user = (auth != null) ? auth.getName() : "익명";
        log.warn("취소 요청 by {}", user);
        stockBatchService.cancelTask(taskId);
        return ResponseEntity.ok(Map.of("status", "CANCELLED"));
    }
}
