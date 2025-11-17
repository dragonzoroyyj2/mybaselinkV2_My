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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ===============================================================
 * 📊 StockBatchAthenaAiService (v4.5 - GlobalSse 통합 정식판)
 * ---------------------------------------------------------------
 * 🔥 GlobalSseService 제거 완료 → GlobalStockService.broadcast() 사용
 * 🔥 전역 SSE / 개별 SSE 완전 연동
 * 🔥 기존 기능/주석 단 1줄도 수정 없음
 * ===============================================================
 */
@Service
public class StockBatchAthenaAiService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchAthenaAiService.class);

    private final TaskStatusService taskStatusService;
    private final GlobalStockService globalStockService;

    @Value("${python.executable.path}")
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

    // 최대 실행 시간: 60초
    private static final long MAX_WAIT_SECONDS = 60L;

    public StockBatchAthenaAiService(
            TaskStatusService taskStatusService,
            GlobalStockService globalStockService
    ) {
        this.taskStatusService = taskStatusService;
        this.globalStockService = globalStockService;
    }

    // ===============================================================
    // 📡 SSE 관리
    // ===============================================================
    public SseEmitter createEmitter(String user) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

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
        initPayload.put("menu", "ATHENA");
        sendTo(emitter, initPayload);

        // 접속 후 200ms 뒤에 현재 상태 1회 추가 전송
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                boolean running = activeLock.get();

                Map<String, Object> statePayload = new LinkedHashMap<>();
                statePayload.put("status", running ? "RUNNING" : "IDLE");
                statePayload.put("runner", currentRunner);
                statePayload.put("progress", 0);
                statePayload.put("globalStatus", running ? "RUNNING" : "IDLE");
                statePayload.put("globalRunner", currentRunner);
                statePayload.put("globalProgress", 0);
                statePayload.put("taskId", currentTaskId);
                statePayload.put("menu", "ATHENA");
                broadcast(statePayload);

                // 🌐 글로벌 SSE에도 상태 전송
                globalStockService.broadcast(
                        running ? "RUNNING" : "IDLE",
                        currentRunner,
                        0
                );
            }
        }, 200);

        return emitter;
    }

    private void sendTo(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("status").data(data));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }

    private void broadcast(Map<String, Object> data) {
        for (SseEmitter e : new ArrayList<>(emitters)) {
            try {
                e.send(SseEmitter.event().name("status").data(data));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    // ===============================================================
    // 🟦 Chart 모드 (기존 그대로)
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

        String logPrefix = "📊 Chart 모드 실행: ";
        log.info("{}symbol={}, ma={}, period={}", logPrefix, symbol, maPeriods, chartPeriod);

        Process p = null;
        StringBuilder outputBuffer = new StringBuilder();

        StringBuilder jsonBuilder = new StringBuilder();
        boolean jsonStarted = false;
        String lastJson = "";

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            p = pb.start();
            log.info("{}Python 프로세스 시작. PID: {}", logPrefix, p.pid());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {

                outputBuffer.append(line).append("\n");

                String trimmed = line.trim();

                if (trimmed.startsWith("{")) {
                    jsonStarted = true;
                    jsonBuilder.setLength(0);
                }
                if (jsonStarted) {
                    jsonBuilder.append(trimmed);
                }
                if (trimmed.endsWith("}")) {
                    lastJson = jsonBuilder.toString();
                    jsonStarted = false;
                }
            }
            reader.close();

            if (!p.waitFor(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new TimeoutException("Python 실행 시간 초과 (" + MAX_WAIT_SECONDS + "초)");
            }

            if (p.exitValue() != 0) {
                throw new RuntimeException("Python 비정상 종료(exit=" + p.exitValue() + ")");
            }

            if (lastJson == null || lastJson.isEmpty()) {
                log.error("{}파이썬 JSON 없음:\n{}", logPrefix, outputBuffer.toString().trim());
                throw new RuntimeException("chart 모드 JSON 출력 없음");
            }

            log.info("{}Raw JSON Length: {}", logPrefix, lastJson.length());
            log.info("{}Clean JSON extracted", logPrefix);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> pythonResult = mapper.readValue(lastJson, Map.class);

            Map<String, Object> finalClientMap = new LinkedHashMap<>();
            finalClientMap.put("mode", pythonResult.get("mode"));
            finalClientMap.put("ticker", pythonResult.get("ticker"));
            finalClientMap.put("name", pythonResult.get("name"));

            if (pythonResult.containsKey("ohlcv_data"))
                finalClientMap.put("ohlcv_data", pythonResult.get("ohlcv_data"));
            if (pythonResult.containsKey("ma_data"))
                finalClientMap.put("ma_data", pythonResult.get("ma_data"));
            if (pythonResult.containsKey("macd_data"))
                finalClientMap.put("macd_data", pythonResult.get("macd_data"));
            if (pythonResult.containsKey("cross_points"))
                finalClientMap.put("cross_points", pythonResult.get("cross_points"));
            if (pythonResult.containsKey("pattern_points"))
                finalClientMap.put("pattern_points", pythonResult.get("pattern_points"));

            try {
                ObjectMapper compact = new ObjectMapper();
                log.info("{}📌 최종 ChartMode JSON 출력(Compact): {}",
                        logPrefix, compact.writeValueAsString(finalClientMap));
            } catch (Exception ignore) {}

            return finalClientMap;

        } catch (Exception e) {
            log.error("{}Chart 모드 예외: {}", logPrefix, e.getMessage(), e);
            log.error("{}전체 출력:\n{}", logPrefix, outputBuffer.toString().trim());
            throw new RuntimeException("chart 모드 실패: " + e.getMessage());
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    // ===============================================================
    // 🔥 Athena AI 분석 시작 (analyze 모드)
    // ===============================================================
    @Async
    public void startUpdate(String taskId, String pattern, String maPeriods,
                            int workers, int topN, String symbol, String username) {

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
        startPayload.put("taskId", currentTaskId);
        startPayload.put("menu", "ATHENA");
        broadcast(startPayload);

        // 🌐 전역 SSE 전송
        globalStockService.broadcast("RUNNING", username, 0);

        Process[] processRef = new Process[1];
        StringBuilder finalJsonBuffer = new StringBuilder();

        try {

            // ===========================================================
            // 🔥 패턴 매핑 (Python pattern_type 과 완전 정합)
            // ===========================================================
            String pythonPattern = switch (pattern) {
                case "long_term_down_trend" -> "all_below_ma";
                case "double_bottom" -> "double_bottom";
                case "triple_bottom" -> "triple_bottom";
                case "cup_and_handle" -> "cup_and_handle";
                case "goldencross" -> "goldencross";
                case "deadcross" -> "deadcross";
                case "half_cup" -> null;  // ❌ 미지원
                default -> pattern;
            };

            if (pythonPattern == null) {
                throw new IllegalStateException("half_cup 패턴은 아직 지원되지 않습니다.");
            }

            // ===========================================================
            // 🔥 analyze_patterns 정확한 조건
            // ===========================================================
            boolean analyzePatternsFlag =
                    !pythonPattern.equals("ma") &&
                    !pythonPattern.equals("all_below_ma") &&
                    !pythonPattern.startsWith("regime:");

            // ===========================================================
            // 실제 실행 커맨드 구성
            // ===========================================================
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(scriptPath);
            cmd.add("--mode");
            cmd.add("analyze");

            cmd.add("--pattern_type");
            cmd.add(pythonPattern);

            cmd.add("--ma_periods");
            cmd.add(maPeriods);

            cmd.add("--workers");
            cmd.add(String.valueOf(workers));

            cmd.add("--top_n");
            cmd.add(String.valueOf(topN));

            if (analyzePatternsFlag) {
                cmd.add("--analyze_patterns");
            }

            if (symbol != null && !symbol.trim().isEmpty()) {
                cmd.add("--symbol");
                cmd.add(symbol);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));

            log.info("🔥 AthenaAI 실행 파일 경로 확인: " + scriptPath);

            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            processRef[0] = pb.start();
            runningProcesses.put(taskId, processRef[0]);

            log.info("🚀 [{}] AthenaAI Python 시작 (pattern={}, pythonPattern={}, ma={}, workers={}, topN={}, symbol={})",
                     taskId, pattern, pythonPattern, maPeriods, workers, topN,
                     (symbol == null ? "None" : symbol));

            Pattern pProgress = Pattern.compile("\"progress_percent\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
            double[] progress = {0.0};
            List<String> logs = new ArrayList<>();
            long[] lastLogTime = {System.currentTimeMillis()};

            // ===========================================================
            // ⏱ hangWatcher (30초 무응답 → 프로세스 강제 kill)
            // ===========================================================
            Future<?> hangMonitor = hangWatcher.scheduleAtFixedRate(() -> {
                long gap = System.currentTimeMillis() - lastLogTime[0];
                if (gap > 30000 && processRef[0] != null && processRef[0].isAlive()) {
                    log.error("⛔ [{}] 30초 이상 로그 없음 → 강제 종료", taskId);
                    try {
                        processRef[0].destroyForcibly();
                        taskStatusService.fail(taskId, "Python 로그 정지 감지됨 (hang)");

                        Map<String, Object> failPayload = new LinkedHashMap<>();
                        failPayload.put("status", "FAILED");
                        failPayload.put("progress", progress[0]);
                        failPayload.put("logs", List.of("[ERROR] Python 프로세스 무응답(hang) 감지"));
                        failPayload.put("globalStatus", "FAILED");
                        failPayload.put("globalRunner", currentRunner);
                        failPayload.put("globalProgress", (int) progress[0]);
                        failPayload.put("taskId", currentTaskId);
                        failPayload.put("menu", "ATHENA");
                        broadcast(failPayload);

                        // 🌐 Global 락 실패 상태 적용
                        globalStockService.broadcast("FAILED", currentRunner, progress[0]);

                    } catch (Exception ex) {
                        log.error("hang 처리 중 예외: {}", ex.getMessage());
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);

            // ===========================================================
            // 📥 PYTHON 실시간 로그 읽기
            // ===========================================================
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
                    if (m1.find()) {
                        progress[0] = safeDouble(m1.group(1));
                    } else if (line.contains("\"mode\":\"progress\"")) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            Map<String, Object> json = mapper.readValue(line.trim(), Map.class);
                            Double val = (Double) json.get("progress_percent");
                            if (val != null) progress[0] = val;
                        } catch (Exception ignore) {}
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", "IN_PROGRESS");
                    payload.put("runner", username);
                    payload.put("progress", progress[0]);
                    payload.put("logs", new ArrayList<>(logs));
                    payload.put("globalStatus", "RUNNING");
                    payload.put("globalRunner", username);
                    payload.put("globalProgress", Math.min(100, Math.max(0, (int) progress[0])));
                    payload.put("taskId", currentTaskId);
                    payload.put("menu", "ATHENA");
                    broadcast(payload);

                    taskStatusService.updateProgress(taskId, progress[0], username);

                    // 🌐 Global SSE 진행률 반영
                    globalStockService.broadcast("RUNNING", username, progress[0]);

                    logs.clear();
                }
            } finally {
                hangMonitor.cancel(true);
            }

            boolean finished = processRef[0].waitFor(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.error("⛔ [{}] Python 실행 시간 초과 ({}초)", taskId, MAX_WAIT_SECONDS);
                taskStatusService.fail(taskId, "Python 실행 시간 초과 (" + MAX_WAIT_SECONDS + "초)");

                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs", List.of("[ERROR] Python 실행 시간 초과"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int) progress[0]);
                failPayload.put("taskId", currentTaskId);
                failPayload.put("menu", "ATHENA");
                broadcast(failPayload);

                // 🌐 Global SSE 실패 알림
                globalStockService.broadcast("FAILED", currentRunner, progress[0]);

                processRef[0].destroyForcibly();
                return;
            }

            int exit = processRef[0].exitValue();
            if (exit != 0) {
                log.error("⛔ [{}] Python 비정상 종료 exit={}", taskId, exit);
                taskStatusService.fail(taskId, "Python 비정상 종료 (exit=" + exit + ")");

                Map<String, Object> failPayload = new LinkedHashMap<>();
                failPayload.put("status", "FAILED");
                failPayload.put("progress", progress[0]);
                failPayload.put("logs", List.of("[ERROR] Python 비정상 종료"));
                failPayload.put("globalStatus", "FAILED");
                failPayload.put("globalRunner", currentRunner);
                failPayload.put("globalProgress", (int) progress[0]);
                failPayload.put("taskId", currentTaskId);
                failPayload.put("menu", "ATHENA");
                broadcast(failPayload);

                // 🌐 Global SSE 실패
                globalStockService.broadcast("FAILED", currentRunner, progress[0]);

                return;
            }
            
            // ===========================================================
            // 🔥 최종 JSON 파싱
            // ===========================================================
            Map<String, Object> resultJson = null;
            try {
                if (finalJsonBuffer.length() > 0) {
                    ObjectMapper mapper = new ObjectMapper();
                    resultJson = mapper.readValue(finalJsonBuffer.toString(), Map.class);
                }
            } catch (Exception ex) {
                log.error("최종 JSON 파싱 실패: {}", ex.getMessage());
            }

            taskStatusService.complete(taskId);

            Map<String, Object> okPayload = new LinkedHashMap<>();
            okPayload.put("status", "COMPLETED");
            okPayload.put("progress", 100);
            okPayload.put("globalStatus", "COMPLETED");
            okPayload.put("globalRunner", currentRunner);
            okPayload.put("globalProgress", 100);
            okPayload.put("taskId", currentTaskId);
            okPayload.put("menu", "ATHENA");
            if (resultJson != null) okPayload.putAll(resultJson);
            broadcast(okPayload);

            // 🌐 Global SSE에도 완료 상태 전달
            globalStockService.broadcast("COMPLETED", currentRunner, 100);

            log.info("🎉 [{}] Athena AI 완료", taskId);

        } catch (Exception e) {

            log.error("🔥 [{}] 예외 발생 (프로세스 시작 포함): {}", taskId, e.getMessage());
            taskStatusService.fail(taskId, "Java 서비스 예외: " + e.getMessage());

            Map<String, Object> failPayload = new LinkedHashMap<>();
            failPayload.put("status", "FAILED");
            failPayload.put("error", e.getMessage());
            failPayload.put("logs", List.of("[ERROR] Java 서비스 예외: " + e.getMessage()));
            failPayload.put("globalStatus", "FAILED");
            failPayload.put("globalRunner", currentRunner);
            failPayload.put("globalProgress", 0);
            failPayload.put("taskId", currentTaskId);
            failPayload.put("menu", "ATHENA");
            broadcast(failPayload);

            // 🌐 Global SSE 반영
            globalStockService.broadcast("FAILED", currentRunner, 0);

        } finally {

            // ===========================================================
            // 🔥 프로세스 정리 (항상 실행)
            // ===========================================================
            try {
                Process p = processRef[0];
                if (p != null) {
                    runningProcesses.remove(taskId);
                    if (p.isAlive()) {
                        log.warn("⚠ [{}] 프로세스 종료 시도 (finally)", taskId);
                        p.destroyForcibly();
                    }
                }
            } catch (Exception ex) {
                log.warn("⚠ [{}] 프로세스 종료 중 예외: {}", taskId, ex.getMessage());
            } finally {

                activeLock.set(false);

                String prev = currentRunner;
                currentRunner = null;
                currentTaskId = null;

                // 🔐 전역락 해제
                globalStockService.releaseLock(taskId);
                log.info("🔓 [{}] 전역 락 해제 (runner={})", taskId, prev);

                // 🌐 전역 SSE → IDLE
                globalStockService.broadcast("IDLE", "-", 0);
            }
        }
    }

    // ===============================================================
    // ❌ 취소
    // ===============================================================
    public boolean cancelTask(String taskId, String username) {

        if (!Objects.equals(taskId, currentTaskId)) return false;
        if (!Objects.equals(username, currentRunner)) return false;

        Process p = runningProcesses.remove(taskId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            log.warn("⛔ [{}] 강제 취소됨 by {}", taskId, username);
        }

        taskStatusService.cancel(taskId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "CANCELLED");
        payload.put("logs", List.of("[LOG] 사용자에 의해 취소되었습니다."));
        payload.put("globalStatus", "CANCELLED");
        payload.put("globalRunner", username);
        payload.put("globalProgress", 0);
        payload.put("taskId", currentTaskId);
        payload.put("menu", "ATHENA");
        broadcast(payload);

        // 🌐 Global SSE에 취소 전파
        globalStockService.broadcast("CANCELLED", username, 0);

        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;

        // 🔐 전역락 해제
        globalStockService.releaseLock(taskId);

        // 🌐 IDLE로 상태 전파
        globalStockService.broadcast("IDLE", "-", 0);

        return true;
    }

    // ===============================================================
    // 🔧 유틸
    // ===============================================================
    private double safeDouble(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return 0.0; }
    }

    public boolean isLocked() { 
        return activeLock.get(); 
    }

    public String getCurrentTaskId() { 
        return currentTaskId; 
    }

    public String getCurrentRunner() { 
        return currentRunner; 
    }
}
            

