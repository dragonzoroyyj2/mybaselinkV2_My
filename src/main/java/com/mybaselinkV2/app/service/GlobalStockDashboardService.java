package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===============================================================
 * 🧭 GlobalStockDashboardService (v1.0 - 전역 상태 대시보드 실전판)
 * ---------------------------------------------------------------
 * ✅ 현재 실행 중인 모든 Python 작업 조회
 * ✅ 실행자/메뉴/진행률/상태/프로세스 생존 여부 확인
 * ✅ 강제 종료 기능 (관리자용)
 * ===============================================================
 */
@Service
public class GlobalStockDashboardService {

    private static final Logger log = LoggerFactory.getLogger(GlobalStockDashboardService.class);

    private final GlobalStockService globalStockService;
    private final TaskStatusService taskStatusService;
    private final Map<String, Process> processMap; // Python 프로세스 맵 (공용)

    public GlobalStockDashboardService(GlobalStockService globalStockService,
                                       TaskStatusService taskStatusService,
                                       Map<String, Process> runningProcesses) {
        this.globalStockService = globalStockService;
        this.taskStatusService = taskStatusService;
        this.processMap = runningProcesses;
    }

    /** ✅ 전체 상태 스냅샷 반환 */
    public List<Map<String, Object>> getAllTaskStatus() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (var entry : globalStockService.getActiveTasks().entrySet()) {
            String taskId = entry.getKey();
            GlobalStockService.GlobalTaskInfo info = entry.getValue();

            boolean alive = false;
            Process p = processMap.get(taskId);
            if (p != null) alive = p.isAlive();

            Map<String, Object> snap = taskStatusService.snapshot(taskId);
            double progress = 0;
            String status = "UNKNOWN";
            if (snap != null && snap.get("result") instanceof Map r && r.get("progress") instanceof Number num)
                progress = ((Number) num).doubleValue();
            if (snap != null && snap.get("status") instanceof String s)
                status = s;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", taskId);
            item.put("menu", info.menu);
            item.put("user", info.user);
            item.put("startTime", new Date(info.startTime));
            item.put("running", info.running);
            item.put("progress", progress);
            item.put("status", status);
            item.put("alive", alive);
            result.add(item);
        }

        return result;
    }

    /** ✅ 강제 종료 (관리자용) */
    public boolean forceKill(String taskId) {
        try {
            Process p = processMap.get(taskId);
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                log.warn("💀 관리자에 의해 프로세스 강제 종료됨: {}", taskId);
            }
            globalStockService.completeTask(taskId);
            taskStatusService.fail(taskId, "관리자 강제 종료");
            return true;
        } catch (Exception e) {
            log.error("⚠️ 강제 종료 실패: {}", e.getMessage());
            return false;
        }
    }
}
