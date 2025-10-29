package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.*;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    @Value("${python.executable.path:}")
    private String pythonExe;
    @Value("${python.update_stock_listing.path:}")
    private String stockUpdateScriptPath;
    @Value("${python.working.dir:}")
    private String pythonWorkingDir;

    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final ConcurrentMap<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<LogLine>> taskLogs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProgressState> progressStates = new ConcurrentHashMap<>();
    private static final int MAX_LOG_LINES = 5000;

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;

    public StockBatchService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    private static final class ProgressState {
        volatile double krxPct = 0.0;
        volatile int dataSaved = 0;
        volatile int dataTotal = 0;
        volatile int krxTotal = 0;
        volatile int krxSaved = 0;
    }

    // ============================================================
    // ✅ 업데이트 시작
    // ============================================================
    @Async
    public void startUpdate(String taskId, boolean force, int workers) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String runner = (auth != null && auth.isAuthenticated()) ? auth.getName() : "알 수 없음";

        // ✅ 이미 락이 잡혀 있을 경우 같은 사용자면 허용, 아니면 차단
        if (activeLock.get()) {
            if (!runner.equals(currentRunner)) {
                throw new IllegalStateException("다른 사용자가 업데이트 중입니다. 잠시 후 다시 시도하세요.");
            } else {
                log.info("같은 사용자가 재시도: {}", runner);
            }
        } else {
            activeLock.set(true);
        }

        currentRunner = runner;
        currentTaskId = taskId;
        Process process = null;

        try {
            taskLogs.put(taskId, new CopyOnWriteArrayList<>());
            ProgressState state = new ProgressState();
            progressStates.put(taskId, state);

            taskStatusService.setTaskStatus(taskId,
                    new TaskStatusService.TaskStatus("IN_PROGRESS",
                            Map.of("progress", 0, "runner", runner, "message", "업데이트 시작 중..."), null));

            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u");
            cmd.add(stockUpdateScriptPath);
            cmd.add("--workers");
            cmd.add(String.valueOf(workers));
            if (force) cmd.add("--force");

            log.info("[{}] Python 실행: {}", taskId, cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(pythonWorkingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            process = pb.start();
            runningProcesses.put(taskId, process);

            Pattern pProg = Pattern.compile("\\[PROGRESS\\]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(.*)");
            Pattern pLog = Pattern.compile("\\[LOG\\]\\s*(.*)");
            Pattern pCnt = Pattern.compile(".*?(\\d+)\\s*/\\s*(\\d+).*");
            Pattern pKrxTotal = Pattern.compile("\\[KRX_TOTAL]\\s*(\\d+)");
            Pattern pKrxSaved = Pattern.compile("\\[KRX_SAVED]\\s*(\\d+)");

            final Process pRef = process;
            ExecutorService ioPool = Executors.newSingleThreadExecutor();

            ioPool.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(pRef.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String L = line.trim();
                        log.info("[PYTHON][{}] {}", taskId, L);

                        Matcher mLog = pLog.matcher(L);
                        if (mLog.find()) appendLog(taskId, mLog.group(1));

                        Matcher mKtot = pKrxTotal.matcher(L);
                        if (mKtot.find()) state.krxTotal = Integer.parseInt(mKtot.group(1));

                        Matcher mKsav = pKrxSaved.matcher(L);
                        if (mKsav.find()) {
                            state.krxSaved = Integer.parseInt(mKsav.group(1));
                            state.krxPct = 100.0;
                        }

                        Matcher mProg = pProg.matcher(L);
                        if (mProg.find()) {
                            double pct = Double.parseDouble(mProg.group(1));
                            String msg = mProg.group(2);

                            Matcher mCnt2 = pCnt.matcher(msg);
                            if (mCnt2.find()) {
                                state.dataSaved = Integer.parseInt(mCnt2.group(1));
                                state.dataTotal = Integer.parseInt(mCnt2.group(2));
                            }

                            taskStatusService.setTaskStatus(taskId,
                                    new TaskStatusService.TaskStatus("IN_PROGRESS",
                                            Map.of("progress", pct, "runner", runner,
                                                    "dataSaved", state.dataSaved,
                                                    "dataTotal", state.dataTotal,
                                                    "krxSaved", state.krxSaved,
                                                    "krxTotal", state.krxTotal,
                                                    "krxPct", state.krxPct,
                                                    "message", msg),
                                            null));
                        }
                    }
                } catch (IOException e) {
                    log.error("[{}] Python 출력 읽기 오류", taskId, e);
                }
            });

            boolean finished = process.waitFor(Duration.ofMinutes(60).toSeconds(), TimeUnit.SECONDS);
            ioPool.shutdownNow();

            if (!finished) {
                process.destroyForcibly();
                setFailed(taskId, "Python 실행 시간 초과");
                return;
            }

            int exit = process.exitValue();
            if (exit != 0) {
                setFailed(taskId, "Python 비정상 종료 (" + exit + ")");
                return;
            }

            setCompleted(taskId, runner);

        } catch (Exception e) {
            log.error("[{}] StockBatch 실행 중 오류", taskId, e);
            setFailed(taskId, e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                try { process.destroyForcibly(); } catch (Exception ignore) {}
            }
            runningProcesses.remove(taskId);
            activeLock.set(false);
            log.info("[{}] 🔓 Lock 해제 완료", taskId);
            currentRunner = null;
            currentTaskId = null;
        }
    }

    // ============================================================
    // ✅ 상태 조회
    // ============================================================
    public Map<String, Object> getStatusWithLogs(String taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        String lookupId = (taskId == null || !progressStates.containsKey(taskId))
                ? currentTaskId : taskId;
        TaskStatusService.TaskStatus s = taskStatusService.getTaskStatus(lookupId);

        if (activeLock.get() && lookupId != null) {
            ProgressState st = progressStates.getOrDefault(lookupId, new ProgressState());
            double progress = (st.dataTotal > 0)
                    ? (st.dataSaved / (double) st.dataTotal) * 100.0
                    : (st.krxTotal > 0 ? (st.krxSaved / (double) st.krxTotal) * 30.0 : 0.0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("progress", progress);
            result.put("runner", currentRunner != null ? currentRunner : "알 수 없음");
            result.put("dataSaved", st.dataSaved);
            result.put("dataTotal", st.dataTotal);
            result.put("krxSaved", st.krxSaved);
            result.put("krxTotal", st.krxTotal);
            result.put("krxPct", st.krxPct);

            body.put("status", (s != null ? s.getStatus() : "LOCKED"));
            body.put("runner", currentRunner);
            body.put("result", result);
            body.put("logs", taskLogs.getOrDefault(lookupId, Collections.emptyList()));

            if (s != null && ("COMPLETED".equals(s.getStatus()) || "CANCELLED".equals(s.getStatus()))) {
                body.put("reset", true);
            }
            return body;
        }

        body.put("status", "NOT_FOUND");
        body.put("message", "현재 실행 중인 작업이 없습니다.");
        return body;
    }

    // ============================================================
    // ✅ 취소 처리
    // ============================================================
    public void cancelTask(String taskId) {
        String lookupId = (taskId == null) ? currentTaskId : taskId;
        Process p = runningProcesses.get(lookupId);
        if (p != null && p.isAlive()) {
            log.warn("[{}] 사용자 요청으로 프로세스 종료", lookupId);
            try { p.destroyForcibly(); } catch (Exception ignore) {}
            appendLog(lookupId, "⏹ 사용자 요청으로 취소됨");
            taskStatusService.setTaskStatus(lookupId,
                    new TaskStatusService.TaskStatus("CANCELLED",
                            Map.of("reset", true, "message", "⏹ 취소됨", "runner", currentRunner),
                            null));
        }
        activeLock.set(false);
        currentRunner = null;
        currentTaskId = null;
    }

    private void appendLog(String taskId, String line) {
        List<LogLine> list = taskLogs.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(new LogLine(list.size() + 1, line));
        if (list.size() > MAX_LOG_LINES) list.remove(0);
    }

    private void setCompleted(String taskId, String runner) {
        appendLog(taskId, "✅ 업데이트 완료");
        taskStatusService.setTaskStatus(taskId,
                new TaskStatusService.TaskStatus("COMPLETED",
                        Map.of("reset", true, "progress", 100, "runner", runner,
                                "message", "✅ 전체 완료"),
                        null));
    }

    private void setFailed(String taskId, String err) {
        taskStatusService.setTaskStatus(taskId,
                new TaskStatusService.TaskStatus("FAILED", null, err));
        appendLog(taskId, "❌ 실패: " + err);
    }

    public boolean isLocked() { return activeLock.get(); }
    public String getCurrentTaskId() { return currentTaskId; }
    public String getCurrentRunner() { return currentRunner; }

    public record LogLine(int seq, String line) {}
}
