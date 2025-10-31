package com.mybaselinkV2.app.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 작업 상태 + 진행률 + 로그 관리 서비스
 * - thread-safe
 * - SSE 전송용 스냅샷 제공
 */
@Service
public class TaskStatusService {

    public static final class LogLine {
        private final int seq;
        private final String line;
        private final Instant ts;

        public LogLine(int seq, String line) {
            this.seq = seq;
            this.line = line;
            this.ts = Instant.now();
        }
        public int getSeq() { return seq; }
        public String getLine() { return line; }
        public Instant getTs() { return ts; }
    }

    public static final class TaskStatus {
        private final String status; // IN_PROGRESS, COMPLETED, CANCELLED, FAILED
        private final Map<String,Object> result; // progress, runner, etc.
        private final String errorMessage;

        public TaskStatus(String status, Map<String,Object> result, String errorMessage) {
            this.status = status;
            this.result = result;
            this.errorMessage = errorMessage;
        }
        public String getStatus() { return status; }
        public Map<String,Object> getResult() { return result; }
        public String getErrorMessage() { return errorMessage; }
    }

    // 상태/로그 저장소
    private final Map<String, TaskStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<String, List<LogLine>> logsMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> logSeqMap = new ConcurrentHashMap<>();

    private static final int MAX_LOG_LINES = 5000;

    /** 상태 저장/갱신 */
    public void setTaskStatus(String taskId, TaskStatus status) {
        statusMap.put(taskId, status);
    }

    /** 상태 조회 */
    public TaskStatus getTaskStatus(String taskId) {
        return statusMap.get(taskId);
    }

    /** 스냅샷(Map) — SSE 전송 등에 사용 */
    @SuppressWarnings("unchecked")
    public Map<String,Object> snapshot(String taskId) {
        Map<String,Object> body = new LinkedHashMap<>();
        TaskStatus s = statusMap.get(taskId);
        if (s == null) {
            body.put("status", "NOT_FOUND");
            body.put("message", "작업을 찾을 수 없습니다.");
            return body;
        }
        body.put("status", s.getStatus());
        Map<String,Object> result = new HashMap<>();
        if (s.getResult() != null) result.putAll(s.getResult());
        body.put("result", result);

        // 최신 로그도 필요하면 붙일 수 있음(지금은 상태만)
        return body;
    }

    /** 로그 추가 */
    public void appendLog(String taskId, String line) {
        List<LogLine> list = logsMap.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        int next = logSeqMap.merge(taskId, 1, Integer::sum);
        list.add(new LogLine(next, line));
        if (list.size() > MAX_LOG_LINES) list.remove(0);
    }

    /** 진행률 갱신 + 러너 유지 */
    public void updateProgress(String taskId, double pct, String runner) {
        Map<String,Object> result = new HashMap<>();
        result.put("progress", pct);
        result.put("runner", runner);
        TaskStatus current = statusMap.get(taskId);
        if (current != null && current.getResult() != null) {
            result.putAll(current.getResult()); // 기존 필드 유지
            result.put("progress", pct);
            result.put("runner", runner);
        }
        setTaskStatus(taskId, new TaskStatus("IN_PROGRESS", result, null));
    }

    /** 완료 처리 */
    public void complete(String taskId) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new HashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());
        result.put("progress", 100);
        setTaskStatus(taskId, new TaskStatus("COMPLETED", result, null));
    }

    /** 취소 처리 */
    public void cancel(String taskId) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new HashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());
        result.put("progress", 0);
        setTaskStatus(taskId, new TaskStatus("CANCELLED", result, "사용자 취소"));
    }

    /** 실패 처리 */
    public void fail(String taskId, String err) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new HashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());
        setTaskStatus(taskId, new TaskStatus("FAILED", result, err));
    }

    /** 로그 조회 */
    public List<LogLine> getLogs(String taskId) {
        return logsMap.getOrDefault(taskId, List.of());
    }
    
    
    /** ✅ 전체 상태 초기화 (재시작 시 100% 깜빡임 방지용) */
    public void reset(String taskId) {
        statusMap.remove(taskId);
        logsMap.remove(taskId);
        logSeqMap.remove(taskId);
    }

}
