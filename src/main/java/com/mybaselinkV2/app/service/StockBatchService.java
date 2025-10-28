package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    // Python 실행 환경
    // ✅ @Value 어노테이션으로 프로퍼티 값 주입
    @Value("${python.executable.path:}")
    private String pythonExe;
    
    @Value("${python.update_stock_listing.path:}")
    private String stockUpdateScriptPath;
    
    @Value("${python.working.dir:}")
    private String pythonWorkingDir;

    // 단일 선점
    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final ConcurrentMap<String, Process> runningProcesses = new ConcurrentHashMap<>();

    // 로그 버퍼
    private final ConcurrentMap<String, List<LogLine>> taskLogs = new ConcurrentHashMap<>();
    private static final int MAX_LOG_LINES = 5000;

    // 진행 상태
    private final ConcurrentMap<String, ProgressState> progressStates = new ConcurrentHashMap<>();

    public StockBatchService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    private static final class ProgressState {
        volatile double krxPct = 0.0; // 0~100
        volatile int dataSaved = 0;
        volatile int dataTotal = 0;
    }

    @Async
    public void startUpdate(String taskId, boolean force, int workers) {
        // ✅ 선점 실패는 곧바로 예외 → 컨트롤러에서 409로 보냄
        if (!activeLock.compareAndSet(false, true)) {
            throw new IllegalStateException("다른 사용자가 업데이트 중입니다. 잠시 후 다시 시도하세요.");
        }

        Process process = null;
        try {
            taskLogs.put(taskId, new CopyOnWriteArrayList<>());
            ProgressState state = new ProgressState();
            progressStates.put(taskId, state);

            Map<String, Object> first = new HashMap<>();
            first.put("progress", 0);
            first.put("message", "업데이트 시작 중...");
            first.put("krxPct", state.krxPct);
            first.put("dataSaved", state.dataSaved);
            first.put("dataTotal", state.dataTotal);
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", first, null));

            // Python 명령어
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add("-u"); // 무버퍼
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
            Pattern pLog  = Pattern.compile("\\[LOG\\]\\s*(.*)");
            Pattern pCnt  = Pattern.compile("종목\\s*저장\\s*(\\d+)\\s*/\\s*(\\d+)");

            // ✅ 실시간 읽기 스레드
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
                        if (mLog.find()) {
                            appendLog(taskId, mLog.group(1));
                        }

                        Matcher mProg = pProg.matcher(L);
                        if (mProg.find()) {
                            double pct = Double.parseDouble(mProg.group(1));
                            String msg = mProg.group(2);

                            // KRX 단계 캐치
                            if (msg.contains("KRX") && msg.contains("다운로드")) {
                                state.krxPct = Math.max(state.krxPct, 30.0);
                            } else if (msg.contains("KRX") && (msg.contains("로드됨") || msg.contains("저장 완료") || msg.contains("완료"))) {
                                state.krxPct = 100.0;
                            }

                            // 종목 카운트 캐치
                            Matcher mCnt = pCnt.matcher(msg);
                            if (mCnt.find()) {
                                try {
                                    state.dataSaved = Integer.parseInt(mCnt.group(1));
                                    state.dataTotal = Integer.parseInt(mCnt.group(2));
                                } catch (Exception ignore) {}
                            }

                            Map<String, Object> res = new HashMap<>();
                            res.put("progress", pct);
                            res.put("message", msg);
                            res.put("krxPct", state.krxPct);
                            res.put("dataSaved", state.dataSaved);
                            res.put("dataTotal", state.dataTotal);

                            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", res, null));
                        }
                    }
                } catch (IOException e) {
                    log.error("[{}] Python 출력 읽기 오류", taskId, e);
                }
            });

            // 타임아웃 60분
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

            setCompleted(taskId);

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
        }
    }

    private void appendLog(String taskId, String line) {
        List<LogLine> list = taskLogs.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(new LogLine(list.size() + 1, line));
        if (list.size() > MAX_LOG_LINES) list.remove(0);
    }

    private void setCompleted(String taskId) {
        ProgressState st = progressStates.getOrDefault(taskId, new ProgressState());
        st.krxPct = 100.0;
        Map<String, Object> res = new HashMap<>();
        res.put("progress", 100);
        res.put("message", "✅ 전체 완료");
        res.put("krxPct", st.krxPct);
        res.put("dataSaved", st.dataSaved);
        res.put("dataTotal", st.dataTotal);
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", res, null));
        appendLog(taskId, "[PROGRESS] 100.0 ✅ 전체 완료");
        appendLog(taskId, "✅ 업데이트 완료");
    }

    private void setFailed(String taskId, String err) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, err));
        appendLog(taskId, "❌ 실패: " + err);
    }

    /** ✅ 상태 조회 */
    public Map<String, Object> getStatusWithLogs(String taskId) {
        TaskStatusService.TaskStatus s = taskStatusService.getTaskStatus(taskId);
        Map<String, Object> body = new LinkedHashMap<>();

        // 작업이 없는데 lock 중이면 → "다른 사용자가 업데이트 중입니다."
        if (s == null) {
            if (activeLock.get()) {
                body.put("status", "FAILED");
                body.put("message", "다른 사용자가 업데이트 중입니다. 잠시 후 다시 시도하세요.");
            } else {
                body.put("status", "NOT_FOUND");
                body.put("message", "작업을 찾을 수 없습니다.");
            }
            return body;
        }

        body.put("status", s.getStatus());
        Map<String, Object> result = new HashMap<>();
        if (s.getResult() != null) result.putAll(s.getResult());

        ProgressState st = progressStates.get(taskId);
        if (st != null) {
            result.put("krxPct", st.krxPct);
            result.put("dataSaved", st.dataSaved);
            result.put("dataTotal", st.dataTotal);
        }

        body.put("result", result);
        if (s.getErrorMessage() != null)
            body.put("errorMessage", s.getErrorMessage());
        body.put("logs", taskLogs.getOrDefault(taskId, Collections.emptyList()));

        return body;
    }

    public void cancelTask(String taskId) {
        Process p = runningProcesses.get(taskId);
        if (p != null && p.isAlive()) {
            log.warn("[{}] 사용자 요청으로 프로세스 종료", taskId);
            try { p.destroyForcibly(); } catch (Exception ignore) {}
            appendLog(taskId, "⏹ 사용자 요청으로 취소됨");
            ProgressState st = progressStates.getOrDefault(taskId, new ProgressState());
            Map<String, Object> res = new HashMap<>();
            res.put("progress", 0);
            res.put("message", "취소됨");
            res.put("krxPct", st.krxPct);
            res.put("dataSaved", st.dataSaved);
            res.put("dataTotal", st.dataTotal);
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("CANCELLED", res, "사용자 취소"));
        } else {
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("CANCELLED",
                    Map.of("message", "취소됨"), "실행 중인 작업이 없습니다."));
        }
        activeLock.set(false);
    }

    public record LogLine(int seq, String line) {}
}
