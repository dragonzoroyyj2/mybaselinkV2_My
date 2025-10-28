package com.mybaselinkV2.app.controller;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselinkV2.app.service.StockBatchService;

@RestController
@RequestMapping("/api/stock/batch")
public class StockBatchController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchController.class);
    private final StockBatchService stockBatchService;

    public StockBatchController(StockBatchService stockBatchService) {
        this.stockBatchService = stockBatchService;
    }

    /**
     * 시작: POST /api/stock/batch/update?workers=8&force=true
     */
    @PostMapping("/update")
    public ResponseEntity<?> startBatchUpdate(@RequestParam(defaultValue = "8") int workers,
                                              @RequestParam(defaultValue = "false") boolean force) {
        String taskId = UUID.randomUUID().toString();
        log.info("📊 전체 종목 업데이트 요청: {}", taskId);

        try {
            stockBatchService.startUpdate(taskId, force, workers);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId));
        } catch (IllegalStateException e) {
            // ✅ 선점 중일 때
            log.warn("[{}] 선점 실패: {}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("업데이트 시작 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "시작 실패: " + e.getMessage()));
        }
    }

    /**
     * 상태: GET /api/stock/batch/status/{taskId}
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(stockBatchService.getStatusWithLogs(taskId));
    }

    /**
     * 취소: POST /api/stock/batch/cancel/{taskId}
     */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        stockBatchService.cancelTask(taskId);
        return ResponseEntity.ok(Map.of("status", "CANCELLED"));
    }
}
