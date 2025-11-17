package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ===============================================================
 * 🧭 GlobalStockService (v2.4 - broadcast 패킷 전역 통일 완성판)
 * ---------------------------------------------------------------
 * ✅ 단일 선점 락(1명) - 전역 관리
 * ✅ 각 메뉴별 Python 작업 상태 저장
 * ✅ 관리자 강제 종료/락 해제/상태조회 지원
 * ✅ cancel 직후 재시작 시 409 방지 (락 잔류 자동정리)
 * ✅ 🌐 전역 SSE 실시간 상태 브로드캐스트 기능 추가
 * ---------------------------------------------------------------
 * 🔥 v2.4 개선 내용
 *    - broadcast() 패킷 구조를 Athena/GProd SSE 패킷과 완전 통합
 *    - menu / taskId 포함 (프런트 전역카드 정상 업데이트)
 * ===============================================================
 */
@Service
public class GlobalStockService {

    private static final Logger log = LoggerFactory.getLogger(GlobalStockService.class);

    /** 현재 락 보유자 정보 */
    private volatile String currentOwner = null;   // username
    private volatile String currentMenu = null;    // GPROD / ATHENA / ...
    private volatile String currentTaskId = null;

    /** 전역 동시 허용 최대 사용자 (기본 1명) */
    private final int maxConcurrent = 1;

    /** 현재 활성 세션 수 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 실행 중인 모든 글로벌 작업 목록 */
    private final Map<String, GlobalTaskInfo> activeTasks = new ConcurrentHashMap<>();

    /** ===============================================================
     * 🌐 SSE Emitter 리스트 (전역 상태 방송용)
     * =============================================================== */
    private final Map<String, SseEmitter> globalEmitters = new ConcurrentHashMap<>();

    /** Emitter 기본 타임아웃 */
    private static final long SSE_TIMEOUT = 1000L * 60 * 30; // 30분

    /** SSE Emitter 생성 */
    public SseEmitter createGlobalEmitter(String user) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String id = UUID.randomUUID().toString();
        globalEmitters.put(id, emitter);

        log.info("🌐 [Global SSE] 연결됨: {} (id={})", user, id);

        emitter.onCompletion(() -> globalEmitters.remove(id));
        emitter.onTimeout(() -> globalEmitters.remove(id));
        emitter.onError((e) -> globalEmitters.remove(id));

        // 연결 직후 이전 상태 즉시 전달
        sendGlobalStatusToEmitter(emitter);

        return emitter;
    }

    /** Emitter 하나에게 상태 전송 */
    private void sendGlobalStatusToEmitter(SseEmitter emitter) {
        try {
            var infoOpt = getCurrentTaskInfo();

            if (infoOpt.isEmpty()) {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of(
                                "status", "GLOBAL",
                                "runner", "-",
                                "progress", 0,
                                "globalStatus", "IDLE",
                                "globalRunner", "-",
                                "globalProgress", 0,
                                "menu", "-",
                                "taskId", "-"
                        )));
                return;
            }

            var info = infoOpt.get();

            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(Map.of(
                            "status", "GLOBAL",
                            "runner", info.user,
                            "progress", 0,
                            "globalStatus", "RUNNING",
                            "globalRunner", info.user,
                            "globalProgress", 0,
                            "menu", info.menu,
                            "taskId", info.taskId
                    )));
        } catch (IOException e) {
            // 무시
        }
    }

    /**
     * ===============================================================
     * 🌐 모든 SSE 구독자에게 상태 전송 (브로드캐스트)
     * ---------------------------------------------------------------
     *  ※ 패킷 구조를 Athena/GProd SSE 패턴과 100% 동일하게 통일
     * ===============================================================
     */
    public void broadcast(String status, String runner, double progress) {

        String menu = currentMenu == null ? "-" : currentMenu;
        String taskId = currentTaskId == null ? "-" : currentTaskId;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "GLOBAL");         // ← 전역 패킷임을 명시
        payload.put("runner", runner);
        payload.put("progress", progress);

        payload.put("globalStatus", status);
        payload.put("globalRunner", runner);
        payload.put("globalProgress", progress);

        payload.put("menu", menu);
        payload.put("taskId", taskId);

        globalEmitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(payload));
            } catch (Exception e) {
                globalEmitters.remove(id);
            }
        });
    }

    // ===============================================================
    // 🔒 기존 락 관리 / 작업 관리 (기능 100% 유지)
    // ===============================================================

    public static class GlobalTaskInfo {
        public final String taskId;
        public final String menu;
        public final String user;
        public final long startTime;
        public volatile boolean running;

        public GlobalTaskInfo(String taskId, String menu, String user) {
            this.taskId = taskId;
            this.menu = menu;
            this.user = user;
            this.startTime = System.currentTimeMillis();
            this.running = true;
        }
    }

    public synchronized boolean acquireLock(String menu, String user, String taskId) {
        if (activeCount.get() >= maxConcurrent) {
            log.warn("🚫 전역 락 거부: 이미 다른 작업 실행 중 (menu={}, owner={})",
                    currentMenu, currentOwner);
            return false;
        }

        this.currentMenu = menu;
        this.currentOwner = user;
        this.currentTaskId = taskId;
        activeCount.incrementAndGet();

        GlobalTaskInfo info = new GlobalTaskInfo(taskId, menu, user);
        activeTasks.put(taskId, info);

        log.info("🔒 전역 락 획득: [{}] by {}", menu, user);

        // 🔔 전역 SSE 알림
        broadcast("RUNNING", user, 0);

        return true;
    }

    public synchronized void releaseLock(String taskId) {
        GlobalTaskInfo info = activeTasks.get(taskId);
        if (info != null) {
            info.running = false;
            activeTasks.remove(taskId);
        }

        if (taskId.equals(this.currentTaskId)) {
            String finishedUser = currentOwner;
            this.currentTaskId = null;
            this.currentOwner = null;
            this.currentMenu = null;
            activeCount.decrementAndGet();
            if (activeCount.get() < 0) activeCount.set(0);

            log.info("🔓 전역 락 해제 완료 (taskId={})", taskId);

            // 🔔 전역 SSE 알림
            broadcast("IDLE", "-", 0);
        }
    }

    public synchronized Optional<GlobalTaskInfo> getCurrentTaskInfo() {
        if (currentTaskId == null) return Optional.empty();
        return Optional.ofNullable(activeTasks.get(currentTaskId));
    }

    public boolean isLocked() {
        return activeCount.get() > 0;
    }

    public Map<String, GlobalTaskInfo> getActiveTasks() {
        return Collections.unmodifiableMap(activeTasks);
    }

    public synchronized void forceReset() {
        activeTasks.clear();
        activeCount.set(0);
        currentMenu = null;
        currentOwner = null;
        currentTaskId = null;

        log.warn("⚠️ GlobalStockService 강제 초기화됨 (관리자 명령)");

        broadcast("IDLE", "-", 0);
    }

    public synchronized void completeTask(String taskId) {
        GlobalTaskInfo info = activeTasks.get(taskId);
        if (info != null) {
            info.running = false;
            log.info("✅ 작업 완료 처리됨: {}", taskId);
            releaseLock(taskId);
        }
    }

    public synchronized void forceUnlockIfNoProcess() {
        if (!isLocked()) return;

        boolean hasRunning = activeTasks.values().stream().anyMatch(t -> t.running);
        if (!hasRunning) {
            log.warn("🧹 잔류 락 자동 해제 (프로세스 없음, owner={})", currentOwner);
            activeTasks.clear();
            activeCount.set(0);
            currentMenu = null;
            currentOwner = null;
            currentTaskId = null;

            broadcast("IDLE", "-", 0);
        }
    }

    public synchronized void unlockForce() {
        if (!isLocked()) return;
        log.warn("🟥 즉시 강제 락 해제 실행 (owner={})", currentOwner);

        activeTasks.clear();
        activeCount.set(0);
        currentMenu = null;
        currentOwner = null;
        currentTaskId = null;

        broadcast("IDLE", "-", 0);
    }

    public String debugStatus() {
        return String.format("[LOCK=%s] owner=%s, menu=%s, activeCount=%d",
                (isLocked() ? "ON" : "OFF"),
                currentOwner,
                currentMenu,
                activeCount.get());
    }
}
