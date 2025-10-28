package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ TaskStatusService (싱글턴 공유형, 실시간 진행률 + 로그 완전반영)
 * 모든 스레드(Async 포함)에서 동일 인스턴스 접근 가능하도록 static 캐시 사용
 */
@Service
public class TaskStatusService {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusService.class);

    // ✅ 모든 쓰레드 공유되는 전역 상태 저장소
    private static final Map<String, TaskStatus> TASK_MAP = new ConcurrentHashMap<>();

    /** 상태 설정 */
    public void setTaskStatus(String taskId, TaskStatus status) {
        if (taskId == null || status == null) return;
        TaskStatus existing = TASK_MAP.get(taskId);
        if (existing != null) {
            existing.setStatus(status.getStatus());
            existing.setResult(status.getResult());
            existing.setErrorMessage(status.getErrorMessage());
            existing.setUpdatedAt(Instant.now());
        } else {
            TASK_MAP.put(taskId, status);
        }
    }

    /** 상태 조회 */
    public TaskStatus getTaskStatus(String taskId) {
        return TASK_MAP.get(taskId);
    }

    /** 로그 추가 */
    public void appendLog(String taskId, String line) {
        if (taskId == null || line == null) return;
        TASK_MAP.compute(taskId, (k, v) -> {
            if (v == null) v = new TaskStatus("IN_PROGRESS", null, null);
            v.addLog(line);
            return v;
        });
    }

    /** 전체 상태 보기 (디버그용) */
    public Map<String, TaskStatus> getAllTasks() {
        return TASK_MAP;
    }

    /** 상태 제거 */
    public void removeTask(String taskId) {
        TASK_MAP.remove(taskId);
    }

    // ==================================
    // 내부 데이터 구조
    // ==================================
    public static class TaskStatus {
        private String status; // IN_PROGRESS, COMPLETED, FAILED, CANCELLED
        private Map<String, Object> result;
        private String errorMessage;
        private Instant updatedAt;
        private final List<LogEntry> logs = Collections.synchronizedList(new ArrayList<>());
        private int logSeq = 0;

        public TaskStatus(String status, Map<String, Object> result, String errorMessage) {
            this.status = status;
            this.result = result;
            this.errorMessage = errorMessage;
            this.updatedAt = Instant.now();
        }

        public synchronized void addLog(String line) {
            logSeq++;
            logs.add(new LogEntry(logSeq, line));
            if (logs.size() > 3000) logs.subList(0, 1000).clear(); // 오래된 로그 제거
        }

        // === getters ===
        public String getStatus() { return status; }
        public Map<String, Object> getResult() { return result; }
        public String getErrorMessage() { return errorMessage; }
        public Instant getUpdatedAt() { return updatedAt; }
        public List<LogEntry> getLogs() { return logs; }
        public int getLogSeq() { return logSeq; }

        // === setters ===
        public void setStatus(String status) { this.status = status; }
        public void setResult(Map<String, Object> result) { this.result = result; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    /** 개별 로그 항목 */
    public static class LogEntry {
        public final int seq;
        public final String line;
        public LogEntry(int seq, String line) {
            this.seq = seq;
            this.line = line;
        }
    }
}
