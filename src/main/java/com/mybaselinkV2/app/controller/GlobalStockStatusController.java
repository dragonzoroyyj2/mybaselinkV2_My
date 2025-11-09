package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.GlobalStockService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ===============================================================
 * 🌐 GlobalStatusController (v1.0 - 실전 안정판)
 * ---------------------------------------------------------------
 * ✅ /api/global/status
 * ✅ 현재 전역(GlobalStockService) 실행 상태 반환
 * ✅ 모든 사용자 접근 가능 (SecurityConfig에서 permitAll)
 * ✅ HTML fetch("/api/global/status") 전용
 * ===============================================================
 */
@RestController
@RequestMapping("/api/global")
public class GlobalStockStatusController {

    private final GlobalStockService globalStockService;
    private final TaskStatusService taskStatusService;

    public GlobalStockStatusController(GlobalStockService globalStockService,
                                  TaskStatusService taskStatusService) {
        this.globalStockService = globalStockService;
        this.taskStatusService = taskStatusService;
    }

    /**
     * ✅ 전역(Global) 상태 조회
     *  - 현재 실행 중인 작업(taskId, runner, progress 등)
     *  - 없으면 IDLE 상태 반환
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        var infoOpt = globalStockService.getCurrentTaskInfo();

        if (infoOpt.isEmpty()) {
            return ResponseEntity.ok(
                java.util.Map.of(
                    "status", "IDLE",
                    "runner", "-",
                    "progress", 0
                )
            );
        }

        var info = infoOpt.get();
        var snapshot = taskStatusService.snapshot(info.taskId);
        double progress = 0.0;

        if (snapshot != null && snapshot.get("result") instanceof java.util.Map<?, ?> resultMap) {
            Object p = resultMap.get("progress");
            if (p instanceof Number) {
                progress = ((Number) p).doubleValue();
            }
        }

        return ResponseEntity.ok(
            java.util.Map.of(
                "status", "RUNNING",
                "runner", info.user,
                "progress", progress
            )
        );
    }
}
