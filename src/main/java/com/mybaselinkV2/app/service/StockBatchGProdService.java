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
 * 📊 StockBatchGProdService (v3.8 - 전역 menu + SSE 전체 반영 완전체)
 * ---------------------------------------------------------------
 * ✅ Python 멈춤(출력 無 15초↑) 자동 FAIL + 즉시 kill
 * ✅ waitFor 3분 초과 시 강제 종료
 * ✅ 실패/예외/타임아웃 시 [ERROR] 로그 자동 전송 (화면 표시)
 * ✅ 전역락 즉시 해제/취소 후 즉시 재시작 가능
 * ✅ 전역 + KRX + 개별 데이터 + 로그 완전 동기화 초기화
 * ✅ 🔥 모든 SSE 패킷(taskId + menu 100% 포함)
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

        boolean running = activeLock.get();

        // ===============================================================
        // 🔥 INIT 패킷 — GPROD 메뉴 반영 (menu:"GPROD" 포함)
        // ===============================================================
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
        initPayload.put("taskId", currentTaskId);
        initPayload.put("menu", "GPROD");          // 🔥 추가됨
        sendTo(emitter, initPayload);

        // ===============================================================
        // 🔥 0.2초 후 상태 패킷 (menu:"GPROD")
        // ===============================================================
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
                statePayload.put("taskId", currentTaskId);
                statePayload.put("menu", "GPROD");      // 🔥 추가됨

                broadcast(statePayload);
            }
        }, 200);

        return emitter;
    }

    private void sendTo(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("status").data(data));
        } catch (Exception e) {
            log.warn("⚠️ SSE send 실패 (정상적인 끊김): {}", e.getMessage());
            emitters.remove(emitter);
        }
    }

    private void broadcast(Map<String, Object> data) {
        for (SseEmitter e : new ArrayList<>(emitters)) {
            try {
                e.send(SseEmitter.event().name("status").data(data));
            } catch (Exception ex) {
                log.warn("⚠️ SSE broadcast 실패 (정상 끊김): {}", ex.getMessage());
                emitters.remove(e);
            }
        }
    }

    // ===============================================================
    // 🚀 업데이트 시작
    // ===============================================================
    @Async
    public void startUpdate(String taskId, boolean force, int workers, int historyYears, String username) {

        // ===============================================================
        // 🔥 GPROD 메뉴로 전역락 선점
        // ===============================================================
        if (!globalStockService.acquireLock("GPROD", username, taskId)) {
            throw new IllegalStateException("다른 사용자가 이미 실행 중입니다.");
        }

        activeLock.set(true);
        currentRunner = username;
        currentTaskId = taskId;
        taskStatusService.reset(taskId);

        // ===============================================================
        // 🔥 INIT 패킷 — menu:"GPROD" 필수 포함
        // ===============================================================
        Map<String, Object> initPayload = new LinkedHashMap<>();
        initPayload.put("status", "INIT");
        initPayload.put("runner", username);
        initPayload.put("progress", 0);
        initPayload.put("globalStatus", "RUNNING");
        initPayload.put("globalRunner", username);
        initPayload.put("globalProgress", 0);
        initPayload.put("krxTotal", 0);
        initPayload.put("krxSaved", 0);
        initPayload.put("dataTotal", 0);
        initPayload.put("dataSaved", 0);
        initPayload.put("logs", List.of("[LOG] 수집 초기화 중..."));
        initPayload.put("errorLogs", new ArrayList<>());
        initPayload.put("taskId", taskId);
        initPayload.put("menu", "GPROD");      // 🔥 추가됨
        broadcast(initPayload);

        // ===============================================================
        // 🔥 START 패킷 — menu:"GPROD"
        // ===============================================================
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                Map<String, Object> startPayload = new LinkedHashMap<>();
                startPayload.put("status", "START");
                startPayload.put("runner", username);
                startPayload.put("progress", 0);
                startPayload.put("globalStatus", "RUNNING");
                startPayload.put("globalRunner", username);
                startPayload.put("globalProgress", 0);
                startPayload.put("taskId", taskId);
                startPayload.put("menu", "GPROD");      // 🔥 추가됨
                broadcast(startPayload);
            }
        }, 200);
        
        Process[] processRef = new Process[1];

        try {
            // ===============================================================
            // 🔧 Python 실행 커맨드 구성
            // ===============================================================
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(scriptPath);
            cmd.add("--workers");
            cmd.add(String.valueOf(workers));
            cmd.add("--history_years");
            cmd.add(String.valueOf(historyYears));
            if (force) cmd.add("--force");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            // ===============================================================
            // 🚀 Python 프로세스 시작
            // ===============================================================
            processRef[0] = pb.start();
            runningProcesses.put(taskId, processRef[0]);
            log.info("🚀 [{}] Python 프로세스 시작됨", taskId);

            // ===============================================================
            // 🔍 패턴 정의
            // ===============================================================
            Pattern pProgress  = Pattern.compile("\\[PROGRESS]\\s*(\\d+(?:\\.\\d+)?)");
            Pattern pKrxTotal  = Pattern.compile("\\[KRX_TOTAL]\\s*(\\d+)");
            Pattern pKrxSaved  = Pattern.compile("\\[KRX_SAVED]\\s*(\\d+)");
            Pattern pDataCount = Pattern.compile("\\((\\d+)/(\\d+)\\)");

            int[] krxTotal = {0}, krxSaved = {0}, dataSaved = {0}, dataTotal = {0};
            double[] progress = {0.0};
            List<String> logs = new ArrayList<>();
            long[] lastLogTime = {System.currentTimeMillis()};

            // ===============================================================
            // 🛑 Hang 감시 (15초 무응답 → 강제 종료)
            // ===============================================================
            Future<?> hangMonitor = hangWatcher.scheduleAtFixedRate(() -> {
                long gap = System.currentTimeMillis() - lastLogTime[0];

                if (gap > 15000 && processRef[0] != null && processRef[0].isAlive()) {
                    log.error("⚠️ [{}] 15초 이상 로그 없음 → 프로세스 강제 종료", taskId);

                    try {
                        processRef[0].destroyForcibly();
                        taskStatusService.fail(taskId,
                                "Python 로그 정지 감지됨 (hang)");

                        Map<String, Object> failPayload = new LinkedHashMap<>();
                        failPayload.put("status", "FAILED");
                        failPayload.put("progress", progress[0]);
                        failPayload.put("logs", List.of("[ERROR] Python 프로세스 비정상 종료 또는 중단 감지됨 (15초 무응답)"));
                        failPayload.put("globalStatus", "FAILED");
                        failPayload.put("globalRunner", currentRunner);
                        failPayload.put("globalProgress", (int)Math.floor(progress[0]));
                        failPayload.put("taskId", taskId);
                        failPayload.put("menu", "GPROD");   // 🔥 추가
                        broadcast(failPayload);

                    } catch (Exception ex) {
                        log.error("❌ [{}] hang 감지 처리 중 예외: {}", taskId, ex.getMessage());
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);


            // ===============================================================
            // 📥 Python stdout 읽기 & 실시간 파싱
            // ===============================================================
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

                    // ===============================================================
                    // 🔥 IN_PROGRESS SSE payload — menu:"GPROD"
                    // ===============================================================
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
                    payload.put("globalProgress",
                            Math.min(100, Math.max(0,(int)Math.floor(progress[0]))));
                    payload.put("taskId", taskId);
                    payload.put("menu", "GPROD");        // 🔥 추가됨

                    broadcast(payload);
                    globalStockService.broadcast("RUNNING", username, progress[0]);   // 🔥 전역 진행률 반영 추가
                    taskStatusService.updateProgress(taskId, progress[0], username);

                    logs.clear();
                }

            } finally {
                hangMonitor.cancel(true);
            }


            // ===============================================================
            // ⏱ waitFor 제한 (3분)
            // ===============================================================
            boolean finished = processRef[0].waitFor(Duration.ofMinutes(3).toSeconds(),
                    TimeUnit.SECONDS);

            if (!finished) {
                log.error("⏱ [{}] Python 실행 시간 초과 - 프로세스 강제 종료", taskId);

                taskStatusService.fail(taskId, "Python 실행 시간 초과");

                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs", List.of("[ERROR] Python 실행 시간 초과 (3분 제한 초과)"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int)Math.floor(progress[0]));
                failPayload.put("taskId", taskId);
                failPayload.put("menu", "GPROD");     // 🔥 추가됨
                broadcast(failPayload);

                processRef[0].destroyForcibly();
                return;
            }


            // ===============================================================
            // 🔥 정상/비정상 종료 코드 체크
            // ===============================================================
            int exit = processRef[0].exitValue();

            if (exit != 0) {
                log.error("❌ [{}] Python 비정상 종료(exitCode={})", taskId, exit);

                taskStatusService.fail(taskId,
                        "Python 비정상 종료(exit=" + exit + ")");

                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs",
                        List.of("[ERROR] Python 비정상 종료 (exitCode=" + exit + ")"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int)Math.floor(progress[0]));
                failPayload.put("taskId", taskId);
                failPayload.put("menu", "GPROD");   // 🔥
                broadcast(failPayload);
                return;
            }


            // ===============================================================
            // 🎉 정상 완료
            // ===============================================================
            taskStatusService.complete(taskId);

            Map<String, Object> completePayload = new LinkedHashMap<>();
            completePayload.put("status", "COMPLETED");
            completePayload.put("progress", 100);
            completePayload.put("globalStatus", "COMPLETED");
            completePayload.put("globalRunner", currentRunner);
            completePayload.put("globalProgress", 100);
            completePayload.put("taskId", taskId);
            completePayload.put("menu", "GPROD");     // 🔥 포함
            broadcast(completePayload);

            log.info("✅ [{}] Python 정상 종료 및 완료", taskId);

            globalStockService.releaseLock(taskId);
        
        } catch (Exception e) {

            // ===============================================================
            // 💥 실행 중 예외 처리 (Java 내부 오류)
            // ===============================================================
            log.error("💥 [{}] 실행 중 예외 발생", taskId, e);
            taskStatusService.fail(taskId, e.getMessage());

            Map<String, Object> failPayload = new LinkedHashMap<>();
            failPayload.put("status", "FAILED");
            failPayload.put("error", e.getMessage());
            failPayload.put("logs",
                    List.of("[ERROR] Java 서비스 예외 발생: " + e.getMessage()));
            failPayload.put("globalStatus", "FAILED");
            failPayload.put("globalRunner", currentRunner);
            failPayload.put("globalProgress", 0);
            failPayload.put("taskId", taskId);
            failPayload.put("menu", "GPROD");       // 🔥 추가됨
            broadcast(failPayload);

        } finally {

            // ===============================================================
            // 🧹 프로세스 정리
            // ===============================================================
            try {
                Process p = runningProcesses.remove(taskId);
                if (p != null && p.isAlive()) {
                    log.warn("💀 [{}] 프로세스 여전히 실행 중 → 강제 종료 시도", taskId);
                    p.destroyForcibly();
                }
            } catch (Exception ex) {
                log.warn("⚠️ [{}] 프로세스 종료 중 예외: {}", taskId, ex.getMessage());
            } finally {

                // ===============================================================
                // 🔓 전역락 해제 + 상태 초기화
                // ===============================================================
                activeLock.set(false);

                String prevRunner = currentRunner;
                currentRunner = null;
                currentTaskId = null;

                globalStockService.releaseLock(taskId);

                log.info("🔓 [{}] 전역 락 해제 완료 (prevRunner={})",
                        taskId, prevRunner);
            }
        }
    }


    // ===============================================================
    // ❌ 취소
    // ===============================================================
    public boolean cancelTask(String taskId, String username) {

        // 본인 실행건만 취소 가능
        if (!Objects.equals(taskId, currentTaskId)) return false;
        if (!Objects.equals(username, currentRunner)) return false;

        // Python 프로세스 종료
        Process p = runningProcesses.remove(taskId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            log.warn("🟥 [{}] 프로세스 강제 취소됨 by {}", taskId, username);
        }

        taskStatusService.cancel(taskId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "CANCELLED");
        payload.put("logs", List.of("[LOG] 사용자에 의해 취소되었습니다."));
        payload.put("globalStatus", "CANCELLED");
        payload.put("globalRunner", username);
        payload.put("globalProgress", 0);
        payload.put("taskId", taskId);
        payload.put("menu", "GPROD");  // 🔥 추가됨
        broadcast(payload);

        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;

        globalStockService.releaseLock(taskId);

        return true;
    }


    // ===============================================================
    // 🔧 유틸
    // ===============================================================
    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private double safeDouble(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return 0.0; }
    }

    // ===============================================================
    // 🔍 Getter
    // ===============================================================
    public boolean isLocked() { return activeLock.get(); }
    public String getCurrentTaskId() { return currentTaskId; }
    public String getCurrentRunner()  { return currentRunner; }
}
