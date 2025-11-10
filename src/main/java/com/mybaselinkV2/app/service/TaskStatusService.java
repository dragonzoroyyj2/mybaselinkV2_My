package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 📊 TaskStatusService (v2.1 실전 통합판)
 * ------------------------------------------------------------
 * ✅ 작업 상태 + 진행률 + 로그 + SSE 스냅샷 관리
 * ✅ thread-safe (ConcurrentHashMap 기반)
 * ✅ Python JSON 결과 파싱 유틸 추가 (parseJsonMap / parseJsonList)
 * ✅ StockBatch / StockLastCloseDownward 등 공용
 * ------------------------------------------------------------
 */
@Service
public class TaskStatusService {

    // ==============================================================
    // 📄 내부 구조체 정의
    // ==============================================================

    /** 🔹 로그 한 줄 정보 */
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

    /** 🔹 작업 상태 정보 */
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

    // ==============================================================
    // 🧠 내부 저장소
    // ==============================================================

    private final Map<String, TaskStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<String, List<LogLine>> logsMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> logSeqMap = new ConcurrentHashMap<>();

    private static final int MAX_LOG_LINES = 5000;

    // ==============================================================
    // ⚙️ 상태 관련 메서드
    // ==============================================================

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

        Map<String,Object> result = new LinkedHashMap<>();
        if (s.getResult() != null) result.putAll(s.getResult());

        body.put("result", result);
        return body;
    }

    // ==============================================================
    // 🪵 로그 관리
    // ==============================================================

    /** 로그 추가 */
    public void appendLog(String taskId, String line) {
        List<LogLine> list = logsMap.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        int next = logSeqMap.merge(taskId, 1, Integer::sum);
        list.add(new LogLine(next, line));
        if (list.size() > MAX_LOG_LINES) list.remove(0);
    }

    /** 로그 조회 */
    public List<LogLine> getLogs(String taskId) {
        return logsMap.getOrDefault(taskId, List.of());
    }

    // ==============================================================
    // 📈 상태 전환
    // ==============================================================

    /** 진행률 갱신 + 러너 유지 */
    public void updateProgress(String taskId, double pct, String runner) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("progress", pct);
        result.put("runner", runner);

        TaskStatus current = statusMap.get(taskId);
        if (current != null && current.getResult() != null) {
            result.putAll(current.getResult());
            result.put("progress", pct);
            result.put("runner", runner);
        }

        setTaskStatus(taskId, new TaskStatus("IN_PROGRESS", result, null));
    }

    /** 완료 처리 */
    public void complete(String taskId) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new LinkedHashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());
        result.put("progress", 100);

        setTaskStatus(taskId, new TaskStatus("COMPLETED", result, null));
    }

    /** 취소 처리 */
    public void cancel(String taskId) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new LinkedHashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());
        result.put("progress", 0);

        setTaskStatus(taskId, new TaskStatus("CANCELLED", result, "사용자 취소"));
    }

    /** 실패 처리 */
    public void fail(String taskId, String err) {
        TaskStatus current = statusMap.get(taskId);
        Map<String,Object> result = new LinkedHashMap<>();
        if (current != null && current.getResult() != null) result.putAll(current.getResult());

        setTaskStatus(taskId, new TaskStatus("FAILED", result, err));
    }

    /** 전체 초기화 (재시작 시 사용) */
    public void reset(String taskId) {
        statusMap.remove(taskId);
        logsMap.remove(taskId);
        logSeqMap.remove(taskId);
    }

    // ==============================================================
    // 🧩 Python JSON 파싱 유틸 (추가)
    // ==============================================================

    private final ObjectMapper mapper = new ObjectMapper();

    /** Python이 반환한 JSON이 `{}` 형태일 때 */
    public Map<String, Object> parseJsonMap(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return new LinkedHashMap<>();
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("JSON 파싱 오류(Map): " + e.getMessage(), e);
        }
    }

    /** Python이 반환한 JSON이 `[]` 형태일 때 */
    public List<Map<String, Object>> parseJsonList(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return Collections.emptyList();
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("JSON 파싱 오류(List): " + e.getMessage(), e);
        }
    }
}
