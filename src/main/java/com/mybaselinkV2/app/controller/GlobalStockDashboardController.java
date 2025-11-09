package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.GlobalStockDashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ===============================================================
 * 📡 GlobalStockDashboardController (v1.0 실전판)
 * ---------------------------------------------------------------
 * ✅ /api/global/stock/dashboard/**
 * ✅ 관리자 전용 전역 상태 대시보드 API
 * ===============================================================
 */
@RestController
@RequestMapping("/api/global/stock/dashboard")
public class GlobalStockDashboardController {

    private final GlobalStockDashboardService dashboardService;

    public GlobalStockDashboardController(GlobalStockDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** ✅ 전체 전역 상태 조회 */
    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        return ResponseEntity.ok(dashboardService.getAllTaskStatus());
    }

    /** ✅ 강제 종료 */
    @PostMapping("/kill/{taskId}")
    public ResponseEntity<Map<String, Object>> kill(@PathVariable String taskId) {
        boolean ok = dashboardService.forceKill(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "killed", ok));
    }
}
