package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskStatusService {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusService.class);

    private static final Map<String, TaskStatus> TASK_MAP = new ConcurrentHashMap<>();

    /** 상태 저장 */
    public void setTaskStatus(String taskId, TaskStatus status) {
        if (taskId == null || status == null) return;
        TASK_MAP.put(taskId, status);
    }

    /** 상태 조회 */
    public TaskStatus getTaskStatus(String taskId) {
        return TASK_MAP.get(taskId);
    }

    /** ✅ StockBatchService 에서 요청한 핵심 기능 */
    public Map<String, Object> getTaskStatusAsMap(String taskId) {
        TaskStatus status = TASK_MAP.get(taskId);
        if (status == null) {
            return Map.of(
                    "status", "NOT_FOUND",
                    "reset", true
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.getStatus());
        body.put("result", status.getResult());
        body.put("errorMessage", status.getErrorMessage());
        body.put("reset", status.getResult() != null ? status.getResult().getOrDefault("reset", false) : false);

        List<Map<String, Object>> logList = new ArrayList<>();
        synchronized (status.getLogs()) {
            for (LogEntry e : status.getLogs()) {
                logList.add(Map.of(
                        "seq", e.seq,
                        "line", e.line
                ));
            }
        }
        body.put("logs", logList);
        return body;
    }

    /** 상태 제거 */
    public void removeTask(String taskId) {
        TASK_MAP.remove(taskId);
    }

    /** 내부 클래스 */
    public static class TaskStatus {
        private String status; // IN_PROGRESS | COMPLETED | FAILED | CANCELLED
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
            if (logs.size() > 3000) logs.subList(0, 1000).clear();
        }

        public String getStatus() { return status; }
        public Map<String, Object> getResult() { return result; }
        public String getErrorMessage() { return errorMessage; }
        public List<LogEntry> getLogs() { return logs; }
        public int getLogSeq() { return logSeq; }

        public void setStatus(String s){ this.status = s; }
        public void setResult(Map<String, Object> r){ this.result = r; }
        public void setErrorMessage(String e){ this.errorMessage = e; }
    }

    public static class LogEntry {
        public final int seq;
        public final String line;
        public LogEntry(int seq, String line) {
            this.seq = seq;
            this.line = line;
        }
    }
}
