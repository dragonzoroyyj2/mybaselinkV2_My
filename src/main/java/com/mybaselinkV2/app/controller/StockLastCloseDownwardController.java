package com.mybaselinkV2.app.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselinkV2.app.service.SockLastCloseDownwardService;
import com.mybaselinkV2.app.service.TaskStatusService;

/**
 * 비동기 작업 처리를 위한 컨트롤러
 */
@RestController
@RequestMapping("/api/stock/last-close-downward")
public class StockLastCloseDownwardController {

    private static final Logger logger = LoggerFactory.getLogger(StockLastCloseDownwardController.class);
    private final SockLastCloseDownwardService sockLastCloseDownwardService;
    private final TaskStatusService taskStatusService;
    
    // 인메모리 활성 작업 추적 (단일 서버 환경용)
    private final AtomicReference<String> activeTaskId = new AtomicReference<>(null);

    @Autowired
    public StockLastCloseDownwardController(SockLastCloseDownwardService sockLastCloseDownwardService, TaskStatusService taskStatusService) {
        this.sockLastCloseDownwardService = sockLastCloseDownwardService;
        this.taskStatusService = taskStatusService;
    }

    /**
     * 상위 N 연속 하락 종목 비동기 조회 요청
     * GET /api/krx/last-close-downward/request?start=2023-01-01&end=2024-01-01&topN=10
     */
    @GetMapping("/request")
    public ResponseEntity<?> requestLastCloseDownward(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "10") int topN
    ) {
        String newTaskId = UUID.randomUUID().toString();
        if (activeTaskId.compareAndSet(null, newTaskId)) {
        	sockLastCloseDownwardService.startLastCloseDownwardTask(newTaskId, start, end, topN);
            return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
    }

    /**
     * 개별 종목 차트 비동기 생성 요청
     * GET /api/krx/last-close-downward/chart/request?baseSymbol=005930&start=2023-01-01&end=2024-01-01
     */
    @GetMapping("/chart/request")
    public ResponseEntity<?> requestChart(
            @RequestParam String baseSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        String newTaskId = UUID.randomUUID().toString();
        if (activeTaskId.compareAndSet(null, newTaskId)) {
        	sockLastCloseDownwardService.startFetchChartTask(newTaskId, baseSymbol, start, end);
            return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "다른 분석 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."));
        }
    }

    /**
     * 작업 상태 조회 및 결과 반환
     * GET /api/krx/task/status?taskId=...
     */
    @GetMapping("/task/status")
    public ResponseEntity<?> getTaskStatus(@RequestParam String taskId) {
        TaskStatusService.TaskStatus status = taskStatusService.getTaskStatus(taskId);
        if ("COMPLETED".equals(status.getStatus()) || "FAILED".equals(status.getStatus())) {
            activeTaskId.compareAndSet(taskId, null);
        }

        // HashMap을 사용하여 null이 가능한 Map을 생성
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("taskId", taskId);
        responseMap.put("status", status.getStatus());
        responseMap.put("result", status.getResult());
        responseMap.put("error", status.getErrorMessage());

        return ResponseEntity.ok(responseMap);
    }
}
