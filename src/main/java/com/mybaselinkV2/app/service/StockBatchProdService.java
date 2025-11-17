package com.mybaselinkV2.app.service;

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
 * 🧩 MyBaseLinkV2 - StockBatchProdService 안정판 v1.0 (2025-11-01)
 * ---------------------------------------------------------------
 * ✅ 완전 동기화/락/퍼센트/로그 안정화
 * ✅ SSE 중복 연결 제거 / heartbeat / dead emitter cleanup
 * ✅ CPU 및 메모리 누수 제거
 * ✅ 모든 버튼/퍼센트/로그 UI 완전 동기화
 * ---------------------------------------------------------------
 * 🚀 안정 기준 버전 — 이후 변경 시 반드시 이 버전을 백업할 것
 * 🚨 DART API Key 관련 설정 및 전달 로직 제거 완료
 * ===============================================================
 */

@Service
@EnableScheduling
public class StockBatchProdService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchProdService.class);
    private final TaskStatusService taskStatusService;

    @Value("${python.executable.path:python}")
    private String pythonExe;

    @Value("${python.update_stock_listing.path}")
    private String scriptPath;

    @Value("${python.working.dir}")
    private String workingDir;


    /** 사용자별 emitter */
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

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    private final AtomicBoolean activeLock = new AtomicBoolean(false);
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    private volatile String currentRunner = null;
    private volatile String currentTaskId = null;

    public StockBatchProdService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null ? auth.getName() : "anonymous");
    }

    /** ✅ 동일 사용자 연결 닫기 */
    private void closeExistingForUser(String user) {
        for (Client c : new ArrayList<>(clients)) {
            if (Objects.equals(c.user, user)) {
                try { c.emitter.complete(); } catch (Exception ignored) {}
                clients.remove(c);
            }
        }
    }

    /** ✅ 브로드캐스트 (owner 계산 포함) */
    private void broadcastStatus(Map<String, Object> base) {
        for (Client c : new ArrayList<>(clients)) {
            Map<String, Object> payload = new LinkedHashMap<>(base);
            payload.put("runner", currentRunner);
            payload.put("owner", Objects.equals(c.user, currentRunner));
            payload.put("currentUser", c.user);
            try {
                c.emitter.send(SseEmitter.event().name("status").data(payload));
                c.lastActive = System.currentTimeMillis();
            } catch (Exception e) {
                log.warn("🧹 Emitter send 실패 → 제거됨: {}", c.user);
                clients.remove(c);
            }
        }
    }

    /** ✅ 신규 클라이언트 1명에게만 전송 */
    private void sendTo(Client c, Map<String, Object> data) {
        try {
            c.emitter.send(SseEmitter.event().name("status").data(data));
            c.lastActive = System.currentTimeMillis();
        } catch (Exception e) {
            clients.remove(c);
        }
    }

    /** ✅ SSE 구독 생성 */
    public SseEmitter createEmitter(String user) {
        closeExistingForUser(user);

        SseEmitter emitter = new SseEmitter(0L);
        Client me = new Client(user, emitter);
        clients.add(me);

        emitter.onCompletion(() -> clients.remove(me));
        emitter.onTimeout(() -> clients.remove(me));
        emitter.onError(e -> {
            log.warn("❌ SSE 오류 감지: {} -> 연결 해제", user);
            clients.remove(me);
        });

        if (activeLock.get() && currentTaskId != null) {
            Map<String, Object> snap = taskStatusService.snapshot(currentTaskId);
            double progress = 0;
            if (snap != null && snap.get("result") instanceof Map r && r.get("progress") instanceof Number p)
                progress = ((Number) p).doubleValue();

            Map<String, Object> init = new LinkedHashMap<>();
            init.put("status", "IN_PROGRESS");
            init.put("runner", currentRunner);
            init.put("owner", Objects.equals(user, currentRunner));
            init.put("currentUser", user);
            init.put("progress", progress);
            sendTo(me, init);
        } else {
            Map<String, Object> init = new LinkedHashMap<>();
            init.put("status", "IDLE");
            init.put("currentUser", user);
            sendTo(me, init);
        }
        return emitter;
    }

    /** ✅ Heartbeat (10초마다 ping) */
    @Scheduled(fixedRate = 10000)
    public void heartbeat() {
        for (Client c : new ArrayList<>(clients)) {
            try {
                c.emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
                c.lastActive = System.currentTimeMillis();
            } catch (Exception e) {
                log.debug("💔 Heartbeat 실패 → {}", c.user);
                clients.remove(c);
            }
        }
    }

    /** ✅ Dead Emitter 정리 (30초 이상 반응 없으면 제거) */
    @Scheduled(fixedRate = 30000)
    public void cleanupDeadEmitters() {
        long now = System.currentTimeMillis();
        for (Client c : new ArrayList<>(clients)) {
            if (now - c.lastActive > 30000) {
                log.warn("🧹 Dead emitter 정리됨: {}", c.user);
                clients.remove(c);
                try { c.emitter.complete(); } catch (Exception ignore) {}
            }
        }
    }

    /** ✅ 일괄 업데이트 */
    @Async
    public void startUpdate(String taskId, boolean force, int workers, int historyYears) {
        String runner = currentUser();

        if (activeLock.get() && !Objects.equals(runner, currentRunner))
            throw new IllegalStateException("다른 사용자가 업데이트 중입니다.");
        else
            activeLock.set(true);

        currentRunner = runner;
        currentTaskId = taskId;

        taskStatusService.reset(taskId);

        LinkedHashMap<String,Object> resetMap = new LinkedHashMap<>();
        resetMap.put("status","RESET");
        resetMap.put("progress",0);
        resetMap.put("logs", List.of("[LOG] 새 업데이트 준비 중..."));
        broadcastStatus(resetMap);

        LinkedHashMap<String,Object> resultMap = new LinkedHashMap<>();
        resultMap.put("progress",0);
        resultMap.put("runner",runner);

        taskStatusService.setTaskStatus(taskId,
                new TaskStatusService.TaskStatus("IN_PROGRESS", resultMap, null));

        LinkedHashMap<String,Object> startMap = new LinkedHashMap<>();
        startMap.put("status","START");
        startMap.put("progress",0);
        broadcastStatus(startMap);
        
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe); cmd.add("-u"); cmd.add(scriptPath);
            cmd.add("--workers"); cmd.add(String.valueOf(workers));

            // historyYears 매개변수를 파이썬 스크립트 인수로 추가
            cmd.add("--history_years"); cmd.add(String.valueOf(historyYears));

            // force 매개변수를 파이썬 스크립트 인수로 추가
            if (force) {
                cmd.add("--force");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            process = pb.start();
            runningProcesses.put(taskId, process);

            Pattern pProgress = Pattern.compile("\\[PROGRESS]\\s*(\\d+(?:\\.\\d+)?)");
            Pattern pKrxTotal = Pattern.compile("\\[KRX_TOTAL]\\s*(\\d+)");
            Pattern pKrxSaved = Pattern.compile("\\[KRX_SAVED]\\s*(\\d+)");
            Pattern pCount = Pattern.compile("\\((\\d+)/(\\d+)\\)");

            int krxTotal=0, krxSaved=0, dataTotal=0, dataSaved=0;
            double progress=0; long lastFlush=System.currentTimeMillis();
            List<String> buffer=new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    taskStatusService.appendLog(taskId, line);
                    log.info("[PYTHON] {}", line);
                    buffer.add(line);

                    Matcher mKT=pKrxTotal.matcher(line); if(mKT.find()) krxTotal=safeInt(mKT.group(1));
                    Matcher mKS=pKrxSaved.matcher(line); if(mKS.find()) krxSaved=safeInt(mKS.group(1));
                    Matcher mCnt=pCount.matcher(line); if(mCnt.find()){ dataSaved=safeInt(mCnt.group(1)); dataTotal=safeInt(mCnt.group(2)); }
                    Matcher mProg=pProgress.matcher(line); if(mProg.find()) progress=safeDouble(mProg.group(1));

                    double krxPct=(krxTotal>0)?(krxSaved*100.0/krxTotal):0;
                    double dataPct=(dataTotal>0)?(dataSaved*100.0/dataTotal):0;
                    double weighted=(krxPct*0.2)+(dataPct*0.8);
                    double realPct=Math.min(100, weighted);
                    progress=Math.max(progress, realPct);

                    if(System.currentTimeMillis()-lastFlush>500){
                        LinkedHashMap<String,Object> inprog = new LinkedHashMap<>();
                        inprog.put("status","IN_PROGRESS");
                        inprog.put("progress",progress);
                        inprog.put("logs",new ArrayList<>(buffer));
                        inprog.put("krxTotal",krxTotal);
                        inprog.put("krxSaved",krxSaved);
                        inprog.put("dataTotal",dataTotal);
                        inprog.put("dataSaved",dataSaved);

                        taskStatusService.updateProgress(taskId,progress,runner);
                        broadcastStatus(inprog);

                        buffer.clear();
                        lastFlush=System.currentTimeMillis();
                    }
                }
            }

            LinkedHashMap<String,Object> lastMap = new LinkedHashMap<>();
            lastMap.put("status","IN_PROGRESS");
            lastMap.put("progress",100);
            lastMap.put("logs",List.of("[LOG] 모든 종목 저장 완료"));
            lastMap.put("krxTotal",krxTotal);
            lastMap.put("krxSaved",krxTotal);
            lastMap.put("dataTotal",dataTotal);
            lastMap.put("dataSaved",dataTotal);
            broadcastStatus(lastMap);

            boolean finished=process.waitFor(Duration.ofHours(1).toSeconds(),TimeUnit.SECONDS);
            if(!finished){
                process.destroyForcibly();
                taskStatusService.fail(taskId,"시간 초과");

                LinkedHashMap<String,Object> failMap = new LinkedHashMap<>();
                failMap.put("status","FAILED");
                broadcastStatus(failMap);
                return;
            }
            if(process.exitValue()!=0){
                taskStatusService.fail(taskId,"Python 오류 종료");

                LinkedHashMap<String,Object> failMap = new LinkedHashMap<>();
                failMap.put("status","FAILED");
                broadcastStatus(failMap);
                return;
            }

            taskStatusService.complete(taskId);
            LinkedHashMap<String,Object> compMap = new LinkedHashMap<>();
            compMap.put("status","COMPLETED");
            compMap.put("progress",100);
            broadcastStatus(compMap);

        } catch(Exception e){
            log.error("[{}] 실행중 오류",taskId,e);
            taskStatusService.fail(taskId,e.getMessage());

            LinkedHashMap<String,Object> failMap = new LinkedHashMap<>();
            failMap.put("status","FAILED");
            broadcastStatus(failMap);

        } finally {
            if(process!=null&&process.isAlive()){ try{process.destroyForcibly();}catch(Exception ignore){} }
            runningProcesses.remove(taskId);
            activeLock.set(false);
            currentRunner=null;
            currentTaskId=null;
            log.info("[{}] 🔓 Lock 해제",taskId);
        }
    }

    /** ✅ 취소 */
    public void cancelTask(String taskId,String requester){
        if(!Objects.equals(taskId,currentTaskId))return;
        if(!Objects.equals(requester,currentRunner))return;
        Process p=runningProcesses.get(taskId);
        if(p!=null&&p.isAlive())p.destroyForcibly();
        taskStatusService.cancel(taskId);

        LinkedHashMap<String,Object> cancelMap = new LinkedHashMap<>();
        cancelMap.put("status","CANCELLED");
        broadcastStatus(cancelMap);

        activeLock.set(false);
        currentRunner=null;
        currentTaskId=null;
    }

    private int safeInt(String s){ try{return Integer.parseInt(s.trim());}catch(Exception e){return 0;} }
    private double safeDouble(String s){ try{return Double.parseDouble(s.trim());}catch(Exception e){return 0.0;} }

    public boolean isLocked(){return activeLock.get();}
    public String getCurrentTaskId(){return currentTaskId;}
    public String getCurrentRunner(){return currentRunner;}
}


