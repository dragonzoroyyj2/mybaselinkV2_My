package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Stock Batch Controller
 * - 비동기 업데이트 시작/취소/상태 확인 관리
 * - SSE 기반 브로드캐스트 병행
 */
@RestController
@RequestMapping("/api/stock/batch")
public class StockBatchController {

    private static final Logger log = LoggerFactory.getLogger(StockBatchController.class);
    private final StockBatchService batchService;
    private final TaskStatusService taskStatusService;

    public StockBatchController(StockBatchService batchService, TaskStatusService taskStatusService) {
        this.batchService = batchService;
        this.taskStatusService = taskStatusService;
    }

    /** ✅ 활성 상태 확인 */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active() {
        boolean locked = batchService.isLocked();
        String taskId = batchService.getCurrentTaskId();
        String runner = batchService.getCurrentRunner();

        if (!locked || taskId == null)
            return ResponseEntity.ok(Map.of("active", false));

        Map<String, Object> snap = taskStatusService.snapshot(taskId);
        Object progress = 0;
        if (snap != null && snap.get("result") instanceof Map r && r.get("progress") instanceof Number p)
            progress = p;

        return ResponseEntity.ok(Map.of(
                "active", true,
                "taskId", taskId,
                "runner", runner != null ? runner : "알 수 없음",
                "progress", progress
        ));
    }

    /** ✅ 상태 조회 */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String taskId) {
        return ResponseEntity.ok(taskStatusService.snapshot(taskId));
    }

    /** ✅ 현재 진행 상태 (프런트 복원용) */
    @GetMapping("/status/current")
    public ResponseEntity<Map<String, Object>> statusCurrent(Authentication auth) {
        String taskId = batchService.getCurrentTaskId();
        String currentUser = (auth != null ? auth.getName() : "anonymous");

        if (taskId == null)
            return ResponseEntity.ok(Map.of("status", "IDLE", "currentUser", currentUser));

        Map<String, Object> snap = taskStatusService.snapshot(taskId);
        snap.put("currentUser", currentUser);  // ✅ 현재 로그인 사용자 포함
        return ResponseEntity.ok(snap);
    }

    /** ✅ 업데이트 시작 */
    @PostMapping("/update")
    public ResponseEntity<?> start(@RequestParam(defaultValue = "8") int workers,
                                   @RequestParam(defaultValue = "false") boolean force,
                                   Authentication auth) {
        String user = (auth != null) ? auth.getName() : "anonymous";
        String taskId = UUID.randomUUID().toString();
        log.info("📊 [{}] 전체 업데이트 요청 by {}", taskId, user);

        try {
            if (batchService.isLocked() && !user.equals(batchService.getCurrentRunner())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "다른 사용자가 업데이트 중입니다.",
                        "runner", batchService.getCurrentRunner(),
                        "active", true
                ));
            }
            batchService.startUpdate(taskId, force, workers);
            return ResponseEntity.accepted().body(Map.of("taskId", taskId, "runner", user));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "runner", batchService.getCurrentRunner(),
                    "active", true
            ));
        } catch (Exception e) {
            log.error("업데이트 시작 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "시작 실패: " + e.getMessage()
            ));
        }
    }

    /** ✅ 취소 */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId, Authentication auth) {
        String user = (auth != null) ? auth.getName() : "anonymous";
        log.warn("⏹ [{}] {}님 취소 요청", taskId, user);
        batchService.cancelTask(taskId, user);
        return ResponseEntity.ok(Map.of("status", "CANCEL_REQUESTED", "currentUser", user));
    }
}
