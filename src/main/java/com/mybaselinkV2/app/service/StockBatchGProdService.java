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
 * 📊 StockBatchGProdService (v3.4 - DART Key 인자전달 완전판)
 * ---------------------------------------------------------------
 * ✅ Python 멈춤(출력 無 15초↑) 자동 FAIL + 즉시 kill
 * ✅ waitFor 3분 초과 시 강제 종료
 * ✅ 실패/예외/타임아웃 시 [ERROR] 로그 자동 전송 (화면 표시)
 * ✅ 전역락 해제/좀비 방지 이중 보정
 * ✅ Spring opendart.dart_api_key → Python --dart_api_key 인자 전달
 * ===============================================================
 */
@Service
public class StockBatchGProdService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchGProdService.class);

    private final TaskStatusService taskStatusService;
    private final GlobalStockService globalStockService;

    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.update_stock_listing_prod.path}")
    private String scriptPath;

    @Value("${python.working.dir}")
    private String workingDir;

    @Value("${opendart.dart_api_key:}")
    private String dart_api_key;  // ✅ Spring 설정값 자동 주입

    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService hangWatcher = Executors.newSingleThreadScheduledExecutor();

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;

    public StockBatchGProdService(TaskStatusService taskStatusService, GlobalStockService globalStockService) {
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
        try { emitter.send(SseEmitter.event().name("status").data(data)); }
        catch (Exception e) { emitters.remove(emitter); }
    }

    private void broadcast(Map<String, Object> data) {
        for (SseEmitter e : new ArrayList<>(emitters)) sendTo(e, data);
    }

    // ===============================================================
    // ✅ 업데이트 시작
    // ===============================================================
    @Async
    public void startUpdate(String taskId, boolean force, int workers, int historyYears, String username) {

        if (!globalStockService.acquireLock("GPROD", username, taskId)) {
            throw new IllegalStateException("다른 사용자가 이미 실행 중입니다.");
        }

        activeLock.set(true);
        currentRunner = username;
        currentTaskId = taskId;
        taskStatusService.reset(taskId);

        broadcast(Map.of(
                "status", "START", "runner", username, "progress", 0,
                "globalStatus", "RUNNING", "globalRunner", username, "globalProgress", 0
        ));

        Process[] processRef = new Process[1];

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(scriptPath);
            cmd.add("--workers");
            cmd.add(String.valueOf(workers));
            cmd.add("--history_years");
            cmd.add(String.valueOf(historyYears));
            if (force) cmd.add("--force");

            // ✅ DART API Key 인자 전달
            if (dart_api_key != null && !dart_api_key.isBlank()) {
                cmd.add("--dart_api_key");
                cmd.add(dart_api_key);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            processRef[0] = pb.start();
            runningProcesses.put(taskId, processRef[0]);
            log.info("🚀 [{}] Python 프로세스 시작됨 (dart_api_key 포함 여부: {})", taskId,
                    (dart_api_key != null && !dart_api_key.isBlank()));

            Pattern pProgress = Pattern.compile("\\[PROGRESS]\\s*(\\d+(?:\\.\\d+)?)");
            Pattern pKrxTotal = Pattern.compile("\\[KRX_TOTAL]\\s*(\\d+)");
            Pattern pKrxSaved = Pattern.compile("\\[KRX_SAVED]\\s*(\\d+)");
            Pattern pDataCount = Pattern.compile("\\((\\d+)/(\\d+)\\)");

            int[] krxTotal = {0}, krxSaved = {0}, dataSaved = {0}, dataTotal = {0};
            double[] progress = {0.0};
            List<String> logs = new ArrayList<>();
            long[] lastLogTime = {System.currentTimeMillis()};

            // ===========================================================
            // 🕒 hang 감시 스레드
            // ===========================================================
            Future<?> hangMonitor = hangWatcher.scheduleAtFixedRate(() -> {
                long gap = System.currentTimeMillis() - lastLogTime[0];
                if (gap > 15000 && processRef[0] != null && processRef[0].isAlive()) {
                    log.error("⚠️ [{}] 15초 이상 로그 없음 → 프로세스 강제 종료", taskId);
                    try {
                        processRef[0].destroyForcibly();
                        taskStatusService.fail(taskId, "Python 로그 정지 감지됨 (hang)");
                        broadcast(Map.of(
                                "status", "FAILED",
                                "progress", progress[0],
                                "logs", List.of("[ERROR] Python 프로세스 비정상 종료 또는 중단 감지됨 (15초 무응답)"),
                                "globalStatus", "FAILED",
                                "globalRunner", currentRunner,
                                "globalProgress", (int)Math.floor(progress[0])
                        ));
                    } catch (Exception ex) {
                        log.error("❌ [{}] hang 감지 처리 중 예외: {}", taskId, ex.getMessage());
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);

            // ===========================================================
            // 🔍 로그 읽기 루프
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
                    Matcher m2 = pKrxTotal.matcher(line);
                    Matcher m3 = pKrxSaved.matcher(line);
                    Matcher m4 = pDataCount.matcher(line);

                    if (m1.find()) progress[0] = safeDouble(m1.group(1));
                    if (m2.find()) krxTotal[0] = safeInt(m2.group(1));
                    if (m3.find()) krxSaved[0] = safeInt(m3.group(1));
                    if (m4.find()) {
                        dataSaved[0] = safeInt(m4.group(1));
                        dataTotal[0] = safeInt(m4.group(2));
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", "IN_PROGRESS");
                    payload.put("runner", username);
                    payload.put("progress", progress[0]);
                    payload.put("krxTotal", krxTotal[0]);
                    payload.put("krxSaved", krxSaved[0]);
                    payload.put("dataTotal", dataTotal[0]);
                    payload.put("dataSaved", dataSaved[0]);
                    payload.put("logs", new ArrayList<>(logs));
                    payload.put("globalStatus", "RUNNING");
                    payload.put("globalRunner", username);
                    payload.put("globalProgress", Math.min(100, Math.max(0, (int)Math.floor(progress[0]))));
                    broadcast(payload);
                    taskStatusService.updateProgress(taskId, progress[0], username);
                    logs.clear();
                }
            } finally {
                hangMonitor.cancel(true);
            }

            boolean finished = processRef[0].waitFor(Duration.ofMinutes(3).toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                log.error("⏱ [{}] Python 실행 시간 초과 - 프로세스 강제 종료", taskId);
                taskStatusService.fail(taskId, "Python 실행 시간 초과");
                broadcast(Map.of(
                        "status", "FAILED", "progress", progress[0],
                        "logs", List.of("[ERROR] Python 실행 시간 초과 (3분 제한 초과)"),
                        "globalStatus", "FAILED", "globalRunner", currentRunner,
                        "globalProgress", (int)Math.floor(progress[0])
                ));
                processRef[0].destroyForcibly();
                return;
            }

            int exit = processRef[0].exitValue();
            if (exit != 0) {
                log.error("❌ [{}] Python 비정상 종료(exitCode={})", taskId, exit);
                taskStatusService.fail(taskId, "Python 비정상 종료(exit=" + exit + ")");
                broadcast(Map.of(
                        "status", "FAILED",
                        "progress", progress[0],
                        "logs", List.of("[ERROR] Python 비정상 종료 (exitCode=" + exit + ")"),
                        "globalStatus", "FAILED",
                        "globalRunner", currentRunner,
                        "globalProgress", (int)Math.floor(progress[0])
                ));
                return;
            }

            taskStatusService.complete(taskId);
            broadcast(Map.of(
                    "status", "COMPLETED", "progress", 100,
                    "globalStatus", "COMPLETED",
                    "globalRunner", currentRunner,
                    "globalProgress", 100
            ));
            log.info("✅ [{}] Python 정상 종료 및 완료", taskId);

        } catch (Exception e) {
            log.error("💥 [{}] 실행 중 예외 발생", taskId, e);
            taskStatusService.fail(taskId, e.getMessage());
            broadcast(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage(),
                    "logs", List.of("[ERROR] Java 서비스 예외 발생: " + e.getMessage()),
                    "globalStatus", "FAILED",
                    "globalRunner", currentRunner,
                    "globalProgress", 0
            ));
        } finally {
            try {
                Process p = runningProcesses.remove(taskId);
                if (p != null && p.isAlive()) {
                    log.warn("💀 [{}] 프로세스 여전히 실행 중 → 강제 종료 시도", taskId);
                    p.destroyForcibly();
                }
            } catch (Exception ex) {
                log.warn("⚠️ [{}] 프로세스 종료 중 예외: {}", taskId, ex.getMessage());
            } finally {
                activeLock.set(false);
                String prevRunner = currentRunner;
                currentRunner = null;
                currentTaskId = null;
                globalStockService.releaseLock(taskId);
                log.info("🔓 [{}] 전역 락 해제 완료 (prevRunner={})", taskId, prevRunner);
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
            log.warn("🟥 [{}] 프로세스 강제 취소됨 by {}", taskId, username);
        }
        taskStatusService.cancel(taskId);
        broadcast(Map.of(
                "status", "CANCELLED",
                "logs", List.of("[LOG] 사용자에 의해 취소되었습니다."),
                "globalStatus", "CANCELLED",
                "globalRunner", username,
                "globalProgress", 0
        ));
        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;
        globalStockService.releaseLock(taskId);
        return true;
    }

    private int safeInt(String s){ try{return Integer.parseInt(s.trim());}catch(Exception e){return 0;} }
    private double safeDouble(String s){ try{return Double.parseDouble(s.trim());}catch(Exception e){return 0.0;} }

    public boolean isLocked(){return activeLock.get();}
    public String getCurrentTaskId(){return currentTaskId;}
    public String getCurrentRunner(){return currentRunner;}
}
