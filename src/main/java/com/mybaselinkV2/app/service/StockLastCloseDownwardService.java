package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ===============================================================
 * 📉 StockLastCloseDownwardService (v3.2 실전 안정판)
 * ---------------------------------------------------------------
 * ✅ stderr + stdout 병행 처리 (97~99% 멈춤 해결)
 * ✅ SSE 완전 동기화
 * ✅ 선점/취소/락/차트 호출 통합
 * ===============================================================
 */
@Service
@EnableScheduling
public class StockLastCloseDownwardService {

    private static final Logger log = LoggerFactory.getLogger(StockLastCloseDownwardService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    public StockLastCloseDownwardService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.find_last_close_downward.path}")
    private String scriptPath;

    @Value("${python.working.dir}")
    private String workingDir;

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean activeLock = new AtomicBoolean(false);

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    /** SSE Client */
    private static final class Client {
        final String user;
        final SseEmitter emitter;
        long lastActive;
        Client(String user, SseEmitter emitter) {
            this.user = user;
            this.emitter = emitter;
            this.lastActive = System.currentTimeMillis();
        }
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null ? auth.getName() : "anonymous");
    }

    public boolean isLocked() { return activeLock.get(); }
    public String getCurrentRunner() { return currentRunner; }
    public String getCurrentTaskId() { return currentTaskId; }

    /** ✅ SSE 연결 */
    public SseEmitter createEmitter(String user) {
        closeExistingForUser(user);
        SseEmitter emitter = new SseEmitter(0L);
        Client me = new Client(user, emitter);
        clients.add(me);

        emitter.onCompletion(() -> clients.remove(me));
        emitter.onTimeout(() -> clients.remove(me));
        emitter.onError(e -> clients.remove(me));

        Map<String, Object> init = new LinkedHashMap<>();
        if (activeLock.get()) {
            init.put("status", "IN_PROGRESS");
            init.put("runner", currentRunner);
            init.put("owner", Objects.equals(user, currentRunner));
        } else {
            init.put("status", "IDLE");
            init.put("currentUser", user);
        }

        sendTo(me, init);
        return emitter;
    }

    private void closeExistingForUser(String user) {
        for (Client c : new ArrayList<>(clients)) {
            if (Objects.equals(c.user, user)) {
                try { c.emitter.complete(); } catch (Exception ignored) {}
                clients.remove(c);
            }
        }
    }

    private void sendTo(Client c, Map<String, Object> data) {
        try {
            c.emitter.send(SseEmitter.event().name("status").data(data));
            c.lastActive = System.currentTimeMillis();
        } catch (Exception e) {
            clients.remove(c);
        }
    }

    private void broadcastStatus(Map<String, Object> data) {
        for (Client c : new ArrayList<>(clients)) {
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put("runner", currentRunner);
            payload.put("owner", Objects.equals(c.user, currentRunner));
            try {
                c.emitter.send(SseEmitter.event().name("status").data(payload));
                c.lastActive = System.currentTimeMillis();
            } catch (Exception e) {
                clients.remove(c);
            }
        }
    }

    @Scheduled(fixedRate = 10000)
    public void heartbeat() {
        for (Client c : new ArrayList<>(clients)) {
            try {
                c.emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
                c.lastActive = System.currentTimeMillis();
            } catch (Exception e) {
                clients.remove(c);
            }
        }
    }

    /** ✅ 취소 */
    public void cancelTask(String taskId, String requester) {
        if (!Objects.equals(taskId, currentTaskId)) return;
        if (!Objects.equals(requester, currentRunner)) return;
        Process p = runningProcesses.get(taskId);
        if (p != null && p.isAlive()) p.destroyForcibly();
        taskStatusService.cancel(taskId);
        broadcastStatus(Map.of("status", "CANCELLED"));
        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;
    }

    /** ✅ 분석 시작 */
    @Async
    public void startAnalysis(String taskId, String start, String end, int topN) {
        String runner = currentUser();
        if (activeLock.get() && !Objects.equals(runner, currentRunner))
            throw new IllegalStateException("다른 사용자가 분석 중입니다.");
        activeLock.set(true);
        currentRunner = runner;
        currentTaskId = taskId;
        taskStatusService.reset(taskId);

        broadcastStatus(Map.of("status", "START", "progress", 0.0, "logs", List.of("[LOG] 분석 시작...")));

        Process process = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<String> cmd = List.of(
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", "ALL",
                    "--start_date", start,
                    "--end_date", end,
                    "--topN", String.valueOf(topN)
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            process = pb.start();
            runningProcesses.put(taskId, process);
            final Process finalProcess = process;

            StringBuilder resultBuf = new StringBuilder();
            List<String> buffer = Collections.synchronizedList(new ArrayList<>());
            Pattern pProgress = Pattern.compile("\\[PROGRESS]\\s*(\\d+(?:\\.\\d+)?)");
            double[] progress = {0.0};
            long[] lastFlush = {System.currentTimeMillis()};

            Future<?> errTask = executor.submit(() -> {
                try (BufferedReader errReader =
                             new BufferedReader(new InputStreamReader(finalProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        taskStatusService.appendLog(taskId, line);
                        log.info("[PYTHON] {}", line);
                        buffer.add(line);
                        Matcher m = pProgress.matcher(line);
                        if (m.find()) progress[0] = Double.parseDouble(m.group(1));
                        if (System.currentTimeMillis() - lastFlush[0] > 400) {
                            broadcastStatus(Map.of("status","IN_PROGRESS","progress",progress[0],"logs",new ArrayList<>(buffer)));
                            buffer.clear(); lastFlush[0] = System.currentTimeMillis();
                        }
                    }
                } catch (IOException ignored) {}
            });

            Future<?> outTask = executor.submit(() -> {
                try (BufferedReader outReader =
                             new BufferedReader(new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = outReader.readLine()) != null) resultBuf.append(line).append("\n");
                } catch (IOException ignored) {}
            });

            errTask.get(); outTask.get(); executor.shutdown();
            int exit = finalProcess.waitFor();

            if (exit == 0) {
                broadcastStatus(Map.of("status","IN_PROGRESS","progress",100,"logs",List.of("[LOG] 분석 완료")));
                String resultJson = resultBuf.toString().trim();
                if (!resultJson.isEmpty()) {
                    List<Map<String,Object>> results = mapper.readValue(resultJson,new TypeReference<>() {});
                    broadcastStatus(Map.of("status","COMPLETED","progress",100,"results",results));
                } else {
                    broadcastStatus(Map.of("status","COMPLETED","progress",100));
                }
            } else {
                broadcastStatus(Map.of("status","FAILED","message","Python 오류 종료"));
            }
        } catch (Exception e) {
            log.error("분석 중 예외", e);
            broadcastStatus(Map.of("status","FAILED","message",e.getMessage()));
        } finally {
            if (process != null) try { process.destroyForcibly(); } catch (Exception ignore) {}
            runningProcesses.remove(taskId);
            activeLock.set(false);
            currentRunner = null;
            currentTaskId = null;
            if (!executor.isShutdown()) executor.shutdownNow();
            log.info("[{}] 🔓 Lock 해제 완료", taskId);
        }
    }

    /** ✅ 개별 차트 생성 */
    public Map<String, Object> generateChart(String symbol, String startDate, String endDate) {
        try {
            List<String> cmd = List.of(
                    pythonExe, "-u", scriptPath,
                    "--symbol", symbol,
                    "--start_date", startDate,
                    "--end_date", endDate
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.environment().put("PYTHONIOENCODING","utf-8");
            Process p = pb.start();

            String result = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
            ).lines().collect(Collectors.joining("\n"));
            p.waitFor();
            if (result.isEmpty()) return Map.of("error","차트 생성 실패");
            return mapper.readValue(result,new TypeReference<>(){});
        } catch (Exception e) {
            log.error("차트 생성 실패", e);
            return Map.of("error", e.getMessage());
        }
    }
}
