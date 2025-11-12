package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ===============================================================
 * 📊 StockBatchAthenaAiService (v4.2 - Python v1.3 완전 동기화판)
 * ---------------------------------------------------------------
 * ✅ analyze 모드 (v1.3 인자체계 완전 일치)
 * ✅ chart 모드 (--mode chart)
 * ✅ SSE INIT + 200ms 브로드캐스트 정상 동작
 * ✅ 새로고침 / 취소 / 락 해제 완전 동기화
 * ===============================================================
 */
@Service
public class StockBatchAthenaAiService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchAthenaAiService.class);

    private final TaskStatusService taskStatusService;
    private final GlobalStockService globalStockService;

    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.athena_k_market_ai_prod.path}")
    private String scriptPath;

    @Value("${python.working.dir}")
    private String workingDir;

    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService hangWatcher = Executors.newSingleThreadScheduledExecutor();

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;

    public StockBatchAthenaAiService(TaskStatusService taskStatusService, GlobalStockService globalStockService) {
        this.taskStatusService = taskStatusService;
        this.globalStockService = globalStockService;
    }

    // ===============================================================
    // ✅ SSE 관리
    // ===============================================================
    public SseEmitter createEmitter(String user) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        boolean running = activeLock.get();

        // ✅ 1차 즉시 INIT 패킷
        Map<String, Object> initPayload = new LinkedHashMap<>();
        initPayload.put("status", "INIT");
        initPayload.put("runner", "-");
        initPayload.put("progress", 0);
        initPayload.put("globalStatus", "IDLE");
        initPayload.put("globalRunner", "-");
        initPayload.put("globalProgress", 0);
        initPayload.put("krxTotal", 0);
        initPayload.put("krxSaved", 0);
        initPayload.put("dataTotal", 0);
        initPayload.put("dataSaved", 0);
        initPayload.put("logs", new ArrayList<>());
        initPayload.put("errorLogs", new ArrayList<>());
        sendTo(emitter, initPayload);

        // ✅ 0.2초 후 실제 상태 브로드캐스트
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                Map<String, Object> statePayload = new LinkedHashMap<>();
                boolean stillRunning = activeLock.get();
                statePayload.put("status", stillRunning ? "RUNNING" : "IDLE");
                statePayload.put("runner", currentRunner);
                statePayload.put("progress", 0);
                statePayload.put("globalStatus", stillRunning ? "RUNNING" : "IDLE");
                statePayload.put("globalRunner", currentRunner);
                statePayload.put("globalProgress", 0);
                broadcast(statePayload);
            }
        }, 200);

        return emitter;
    }

    private void sendTo(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("status").data(data));
        } catch (Exception e) {
            log.warn("⚠️ SSE send 실패: {}", e.getMessage());
            emitters.remove(emitter);
        }
    }

    private void broadcast(Map<String, Object> data) {
        for (SseEmitter e : new ArrayList<>(emitters)) {
            try {
                e.send(SseEmitter.event().name("status").data(data));
            } catch (Exception ex) {
                log.warn("⚠️ SSE broadcast 실패: {}", ex.getMessage());
                emitters.remove(e);
            }
        }
    }

    // ===============================================================
    // ✅ Chart 모드 (락 없음 / SSE 없음)
    // ===============================================================
    public Map<String, Object> runChartMode(String symbol, String maPeriods, int chartPeriod) {

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExe);
        cmd.add("-u");
        cmd.add(scriptPath);
        cmd.add("--mode");
        cmd.add("chart");
        cmd.add("--symbol");
        cmd.add(symbol);
        cmd.add("--ma_periods");
        cmd.add(maPeriods);
        cmd.add("--chart_period");
        cmd.add(String.valueOf(chartPeriod));

        log.info("📈 Chart 모드 실행: symbol={}, ma={}, period={}", symbol, maPeriods, chartPeriod);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            Process p = pb.start();
            StringBuilder jsonBuf = new StringBuilder();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.info("[PYTHON chart] {}", line);
                    if (line.trim().startsWith("{") && line.trim().endsWith("}")) {
                        jsonBuf.setLength(0);
                        jsonBuf.append(line.trim());
                    }
                }
            }

            p.waitFor();

            if (jsonBuf.length() == 0)
                throw new RuntimeException("파이썬 chart 모드 JSON 출력 없음");

            return new ObjectMapper().readValue(jsonBuf.toString(), Map.class);

        } catch (Exception e) {
            log.error("❌ Chart 모드 예외: {}", e.getMessage());
            throw new RuntimeException("chart 모드 실패: " + e.getMessage());
        }
    }

    // ===============================================================
    // ✅ Athena AI 분석 시작 (analyze 모드)
    // ===============================================================
    @Async
    public void startUpdate(String taskId, String pattern, String maPeriods, int workers, int topN, String symbol, String username) {

        if (!globalStockService.acquireLock("ATHENA", username, taskId)) {
            throw new IllegalStateException("다른 사용자가 이미 실행 중입니다.");
        }

        activeLock.set(true);
        currentRunner = username;
        currentTaskId = taskId;

        taskStatusService.reset(taskId);

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("status", "START");
        startPayload.put("runner", username);
        startPayload.put("progress", 0);
        startPayload.put("globalStatus", "RUNNING");
        startPayload.put("globalRunner", username);
        startPayload.put("globalProgress", 0);
        broadcast(startPayload);

        Process[] processRef = new Process[1];
        StringBuilder finalJsonBuffer = new StringBuilder();

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(scriptPath);
            cmd.add("--mode");
            cmd.add("analyze");
            cmd.add("--pattern_type");
            cmd.add(pattern);
            cmd.add("--ma_periods");
            cmd.add(maPeriods);
            cmd.add("--workers");
            cmd.add(String.valueOf(workers));
            cmd.add("--top_n");
            cmd.add(String.valueOf(topN));
            cmd.add("--analyze_patterns"); // ✅ Python v1.3 완전 동기화 핵심 추가
            if (symbol != null && !symbol.trim().isEmpty()) {
                cmd.add("--symbol");
                cmd.add(symbol);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            processRef[0] = pb.start();
            runningProcesses.put(taskId, processRef[0]);

            log.info("🚀 [{}] AthenaAI Python 시작 (pattern={}, maPeriods={}, workers={}, topN={}, symbol={})",
                    taskId, pattern, maPeriods, workers, topN, symbol == null ? "None" : symbol);

            Pattern pProgress = Pattern.compile("\"progress_percent\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
            double[] progress = {0.0};
            List<String> logs = new ArrayList<>();
            long[] lastLogTime = {System.currentTimeMillis()};

            Future<?> hangMonitor = hangWatcher.scheduleAtFixedRate(() -> {
                long gap = System.currentTimeMillis() - lastLogTime[0];
                if (gap > 15000 && processRef[0] != null && processRef[0].isAlive()) {
                    log.error("⚠️ [{}] 15초 이상 로그 없음 → 강제 종료", taskId);
                    try {
                        processRef[0].destroyForcibly();
                        taskStatusService.fail(taskId, "Python 로그 정지 감지됨 (hang)");
                        Map<String, Object> failPayload = new LinkedHashMap<>();
                        failPayload.put("status", "FAILED");
                        failPayload.put("progress", progress[0]);
                        failPayload.put("logs", List.of("[ERROR] Python 프로세스 무응답(hang) 감지"));
                        failPayload.put("globalStatus", "FAILED");
                        failPayload.put("globalRunner", currentRunner);
                        failPayload.put("globalProgress", (int) Math.floor(progress[0]));
                        broadcast(failPayload);
                    } catch (Exception ex) {
                        log.error("❌ hang 처리 예외: {}", ex.getMessage());
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(processRef[0].getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastLogTime[0] = System.currentTimeMillis();
                    if (line.trim().startsWith("{") && line.trim().endsWith("}")) {
                        finalJsonBuffer.setLength(0);
                        finalJsonBuffer.append(line.trim());
                    }
                    logs.add(line);
                    taskStatusService.appendLog(taskId, line);
                    log.info("[PYTHON] {}", line);
                    Matcher m1 = pProgress.matcher(line);
                    if (m1.find()) progress[0] = safeDouble(m1.group(1));

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", "IN_PROGRESS");
                    payload.put("runner", username);
                    payload.put("progress", progress[0]);
                    payload.put("logs", new ArrayList<>(logs));
                    payload.put("globalStatus", "RUNNING");
                    payload.put("globalRunner", username);
                    payload.put("globalProgress", Math.min(100, Math.max(0, (int) Math.floor(progress[0]))));
                    broadcast(payload);
                    taskStatusService.updateProgress(taskId, progress[0], username);
                    logs.clear();
                }
            } finally {
                hangMonitor.cancel(true);
            }

            boolean finished = processRef[0].waitFor(Duration.ofMinutes(3).toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                log.error("⏱ [{}] Python 실행 시간 초과", taskId);
                taskStatusService.fail(taskId, "Python 실행 시간 초과");
                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs", List.of("[ERROR] Python 실행 시간 초과 (3분)"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int) Math.floor(progress[0]));
                broadcast(failPayload);
                processRef[0].destroyForcibly();
                return;
            }

            int exit = processRef[0].exitValue();
            if (exit != 0) {
                log.error("❌ [{}] Python 비정상 종료 exit={}", taskId, exit);
                taskStatusService.fail(taskId, "Python 비정상 종료(exit=" + exit + ")");
                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs", List.of("[ERROR] Python 비정상 종료(exit=" + exit + ")"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int) Math.floor(progress[0]));
                broadcast(failPayload);
                return;
            }

            Map<String, Object> resultJson = null;
            try {
                if (finalJsonBuffer.length() > 0) {
                    resultJson = new ObjectMapper().readValue(finalJsonBuffer.toString(), Map.class);
                }
            } catch (Exception ex) {
                log.error("❌ 최종 JSON 파싱 실패: {}", ex.getMessage());
            }

            taskStatusService.complete(taskId);
            Map<String, Object> okPayload = new LinkedHashMap<>();
            okPayload.put("status", "COMPLETED");
            okPayload.put("progress", 100);
            okPayload.put("globalStatus", "COMPLETED");
            okPayload.put("globalRunner", currentRunner);
            okPayload.put("globalProgress", 100);
            if (resultJson != null) okPayload.putAll(resultJson);
            broadcast(okPayload);
            log.info("✅ [{}] Athena AI 완료", taskId);

        } catch (Exception e) {
            log.error("💥 [{}] 예외 발생", taskId, e);
            taskStatusService.fail(taskId, e.getMessage());
            Map<String, Object> failPayload = new LinkedHashMap<>();
            failPayload.put("status", "FAILED");
            failPayload.put("error", e.getMessage());
            failPayload.put("logs", List.of("[ERROR] Java 서비스 예외: " + e.getMessage()));
            failPayload.put("globalStatus", "FAILED");
            failPayload.put("globalRunner", currentRunner);
            failPayload.put("globalProgress", 0);
            broadcast(failPayload);

        } finally {
            try {
                Process p = runningProcesses.remove(taskId);
                if (p != null && p.isAlive()) {
                    log.warn("💀 [{}] 프로세스 종료 시도", taskId);
                    p.destroyForcibly();
                }
            } catch (Exception ex) {
                log.warn("⚠️ [{}] 프로세스 종료 중 예외: {}", taskId, ex.getMessage());
            } finally {
                activeLock.set(false);
                String prev = currentRunner;
                currentRunner = null;
                currentTaskId = null;
                globalStockService.releaseLock(taskId);
                log.info("🔓 [{}] 전역 락 해제 (runner={})", taskId, prev);
            }
        }
    }

    // ===============================================================
    // ✅ 취소
    // ===============================================================
    public boolean cancelTask(String taskId, String username) {
        if (!Objects.equals(taskId, currentTaskId)) return false;
        if (!Objects.equals(username, currentRunner)) return false;
        Process p = runningProcesses.remove(taskId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            log.warn("🟥 [{}] 강제 취소됨 by {}", taskId, username);
        }
        taskStatusService.cancel(taskId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "CANCELLED");
        payload.put("logs", List.of("[LOG] 사용자에 의해 취소되었습니다."));
        payload.put("globalStatus", "CANCELLED");
        payload.put("globalRunner", username);
        payload.put("globalProgress", 0);
        broadcast(payload);
        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;
        globalStockService.releaseLock(taskId);
        return true;
    }

    // ===============================================================
    // ✅ 유틸
    // ===============================================================
    private double safeDouble(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return 0.0; }
    }

    public boolean isLocked() { return activeLock.get(); }
    public String getCurrentTaskId() { return currentTaskId; }
    public String getCurrentRunner() { return currentRunner; }
}
