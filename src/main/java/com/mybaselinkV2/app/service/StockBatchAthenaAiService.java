package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
 * 📊 StockBatchAthenaAiService (v3.6 - 실전 완전판)
 * ---------------------------------------------------------------
 * ✅ Python 멈춤(출력 無 15초↑) 자동 FAIL + 즉시 kill
 * ✅ waitFor 3분 초과 시 강제 종료
 * ✅ 실패/예외/타임아웃 시 [ERROR] 로그 자동 전송
 * ✅ 전역락 해제/좀비 방지 이중 보정
 * ✅ Athena AI 분석용 인자 (--mode analyze, --pattern_type, --workers, --years, --exclude_negatives)
 * ===============================================================
 */
@Service
public class StockBatchAthenaAiService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchAthenaAiService.class);

    private final TaskStatusService taskStatusService;
    private final GlobalStockService globalStockService;

    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.athena_k_market_ai.path}")
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

        Map<String, Object> payload = new LinkedHashMap<>();
        boolean running = activeLock.get();

        payload.put("status", running ? "RUNNING" : "IDLE");
        payload.put("runner", currentRunner);
        payload.put("progress", 0);
        payload.put("globalStatus", running ? "RUNNING" : "IDLE");
        payload.put("globalRunner", currentRunner);
        payload.put("globalProgress", 0);

        sendTo(emitter, payload);
        return emitter;
    }

    private void sendTo(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("status").data(data));
        } catch (Exception e) {
            log.warn("⚠️ SSE send 실패 (정상 현상, 오류 아님. SSE 특성). {}", e.getMessage());
            emitters.remove(emitter);
        }
    }

    private void broadcast(Map<String, Object> data) {
        for (SseEmitter e : new ArrayList<>(emitters)) {
            try {
                e.send(SseEmitter.event().name("status").data(data));
            } catch (Exception ex) {
                log.warn("⚠️ SSE broadcast 실패 (정상 현상, SSE 특성). {}", ex.getMessage());
                emitters.remove(e);
            }
        }
    }

    // ===============================================================
    // ✅ Athena AI 분석 시작
    // ===============================================================
    @Async
    public void startUpdate(String taskId, String pattern, boolean excludeNeg, int workers, int years, String username) {

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

        try {
            // ===========================================================
            // ✅ Python 명령어
            // ===========================================================
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(scriptPath);

            cmd.add("--mode");
            cmd.add("analyze");
            cmd.add("--pattern_type");
            cmd.add(pattern);

            cmd.add("--workers");
            cmd.add(String.valueOf(workers));
            cmd.add("--years");
            cmd.add(String.valueOf(years));
            if (excludeNeg) cmd.add("--exclude_negatives");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            processRef[0] = pb.start();
            runningProcesses.put(taskId, processRef[0]);

            log.info("🚀 [{}] AthenaAI Python 시작 (pattern={}, years={}, excludeNeg={})", taskId, pattern, years, excludeNeg);

            Pattern pProgress = Pattern.compile("\"progress_percent\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");

            double[] progress = {0.0};
            List<String> logs = new ArrayList<>();
            long[] lastLogTime = {System.currentTimeMillis()};

            // ===========================================================
            // 🕒 Hang 감시
            // ===========================================================
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

            // ===========================================================
            // ✅ 로그 읽기 루프
            // ===========================================================
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(processRef[0].getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {

                    lastLogTime[0] = System.currentTimeMillis();
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
                    payload.put("globalProgress",
                            Math.min(100, Math.max(0, (int) Math.floor(progress[0]))));

                    broadcast(payload);
                    taskStatusService.updateProgress(taskId, progress[0], username);

                    logs.clear();
                }
            } finally {
                hangMonitor.cancel(true);
            }

            boolean finished = processRef[0].waitFor(
                    Duration.ofMinutes(3).toSeconds(),
                    TimeUnit.SECONDS
            );

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

            // ✅ 정상 완료
            taskStatusService.complete(taskId);

            Map<String, Object> okPayload = new LinkedHashMap<>();
            okPayload.put("status", "COMPLETED");
            okPayload.put("progress", 100);
            okPayload.put("globalStatus", "COMPLETED");
            okPayload.put("globalRunner", currentRunner);
            okPayload.put("globalProgress", 100);

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

                globalStockService.releaseLock("ATHENA");

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

        globalStockService.releaseLock("ATHENA");

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
