package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

@Service
public class StockBatchService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchService.class);
    private final TaskStatusService taskStatusService;

    // ✅ Python 경로 설정 복원
    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.update_stock_listing.path}")
    private String scriptPath;

    @Value("${python.working.dir}")
    private String workingDir;

    // ✅ SSE client pool
    private final CopyOnWriteArrayList<SseEmitter> sseClients = new CopyOnWriteArrayList<>();

    // ✅ 단일 락
    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;

    public StockBatchService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    /** ✅ SSE 연결 */
    public SseEmitter createEmitter(String user) {
        SseEmitter emitter = new SseEmitter(0L);
        sseClients.add(emitter);

        emitter.onCompletion(() -> sseClients.remove(emitter));
        emitter.onTimeout(() -> sseClients.remove(emitter));
        emitter.onError(e -> sseClients.remove(emitter));

        if (activeLock.get() && currentTaskId != null) {
            Map<String, Object> snap = taskStatusService.snapshot(currentTaskId);
            snap.put("runner", currentRunner);
            snap.put("owner", Objects.equals(user, currentRunner));
            safeSendSse(Map.of("status", "IN_PROGRESS", "runner", currentRunner, "owner", Objects.equals(user, currentRunner), "progress", snap.getOrDefault("progress", 0)));
        } else {
            safeSendSse(Map.of("status", "IDLE"));
        }

        return emitter;
    }

    private void safeSendSse(Object data) {
        for (SseEmitter emitter : new ArrayList<>(sseClients)) {
            try {
                emitter.send(SseEmitter.event().name("status").data(data));
            } catch (Exception e) {
                sseClients.remove(emitter);
            }
        }
    }

    /** ✅ 일괄 업데이트 시작 */
    @Async
    public void startUpdate(String taskId, boolean force, int workers) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String runner = auth != null ? auth.getName() : "알 수 없음";

        if (activeLock.get() && !Objects.equals(runner, currentRunner)) {
            throw new IllegalStateException("다른 사용자가 업데이트 중입니다.");
        } else {
            activeLock.set(true);
        }

        currentRunner = runner;
        currentTaskId = taskId;

        taskStatusService.setTaskStatus(taskId,
                new TaskStatusService.TaskStatus("IN_PROGRESS",
                        new HashMap<>(Map.of("progress", 0, "runner", runner)),
                        null));

        safeSendSse(Map.of("status","START","runner",runner,"owner",true,"progress",0));

        Process process = null;

        try {
            // ✅ Python 실행 (환경 기반)
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExe, "-u", scriptPath,
                    "--workers", String.valueOf(workers),
                    force ? "--force" : ""
            );

            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            process = pb.start();
            runningProcesses.put(taskId, process);

            Pattern p = Pattern.compile("\\[PROGRESS]\\s*(\\d+(?:\\.\\d+)?)");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    taskStatusService.appendLog(taskId, line);
                    Matcher m = p.matcher(line);

                    if (m.find()) {
                        double pct = Double.parseDouble(m.group(1));
                        taskStatusService.updateProgress(taskId, pct, runner);

                        safeSendSse(Map.of("status","IN_PROGRESS","runner",runner,"owner",true,"progress",pct));
                    }
                }
            }

            boolean finished = process.waitFor(Duration.ofHours(1).toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                taskStatusService.fail(taskId, "시간 초과");
                safeSendSse(Map.of("status","FAILED","runner",runner,"owner",true));
                return;
            }

            if (process.exitValue() != 0) {
                taskStatusService.fail(taskId, "Python 오류 종료");
                safeSendSse(Map.of("status","FAILED","runner",runner,"owner",true));
                return;
            }

            taskStatusService.complete(taskId);
            safeSendSse(Map.of("status","COMPLETED","runner",runner,"owner",true,"progress",100));

        } catch (Exception e) {
            log.error("[{}] 실행중 오류",taskId,e);
            taskStatusService.fail(taskId,e.getMessage());
            safeSendSse(Map.of("status","FAILED","runner",runner,"owner",true));
        } finally {
            if (process != null && process.isAlive()) {
                try { process.destroyForcibly(); } catch (Exception ignore) {}
            }
            runningProcesses.remove(taskId);
            activeLock.set(false);
            currentRunner = null;
            currentTaskId = null;
            log.info("[{}] 🔓 Lock 해제",taskId);
        }
    }

    /** ✅ 취소 */
    public void cancelTask(String taskId, String requester) {
        if (!Objects.equals(taskId, currentTaskId)) return;
        if (!Objects.equals(requester, currentRunner)) return;

        Process p = runningProcesses.get(taskId);
        if (p != null && p.isAlive()) p.destroyForcibly();

        taskStatusService.cancel(taskId);
        safeSendSse(Map.of("status","CANCELLED","runner",currentRunner,"owner",true));

        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;
    }

    public boolean isLocked() { return activeLock.get(); }
    public String getCurrentTaskId() { return currentTaskId; }
    public String getCurrentRunner() { return currentRunner; }
}
