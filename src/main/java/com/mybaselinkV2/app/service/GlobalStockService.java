package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ===============================================================
 * 🧭 GlobalStockService (v2.2 - 즉시 강제 해제 + 자동정리 안정판)
 * ---------------------------------------------------------------
 * ✅ 단일 선점 락(1명) - 전역 관리
 * ✅ 각 메뉴별 Python 작업 상태 저장
 * ✅ 관리자 강제 종료/락 해제/상태조회 지원
 * ✅ cancel 직후 재시작 시 409 방지 (락 잔류 자동정리)
 * ===============================================================
 */
@Service
public class GlobalStockService {

    private static final Logger log = LoggerFactory.getLogger(GlobalStockService.class);

    /** 현재 락 보유자 정보 */
    private volatile String currentOwner = null;
    private volatile String currentMenu = null;
    private volatile String currentTaskId = null;

    /** 전역 동시 허용 최대 사용자 (기본 1명) */
    private final int maxConcurrent = 1;

    /** 현재 활성 세션 수 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 실행 중인 모든 글로벌 작업 목록 */
    private final Map<String, GlobalTaskInfo> activeTasks = new ConcurrentHashMap<>();

    // ===============================================================
    // ✅ 내부 데이터 클래스
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

    // ===============================================================
    // ✅ 락 선점
    // ===============================================================
    public synchronized boolean acquireLock(String menu, String user, String taskId) {
        // 이미 락이 존재하면 false
        if (activeCount.get() >= maxConcurrent) {
            log.warn("🚫 전역 락 거부: 이미 다른 작업 실행 중 (menu={}, owner={})", currentMenu, currentOwner);
            return false;
        }

        this.currentMenu = menu;
        this.currentOwner = user;
        this.currentTaskId = taskId;
        activeCount.incrementAndGet();

        GlobalTaskInfo info = new GlobalTaskInfo(taskId, menu, user);
        activeTasks.put(taskId, info);

        log.info("🔒 전역 락 획득: [{}] by {}", menu, user);
        return true;
    }

    // ===============================================================
    // ✅ 락 해제 (정상 완료 or 예외 or 취소)
    // ===============================================================
    public synchronized void releaseLock(String taskId) {
        GlobalTaskInfo info = activeTasks.get(taskId);
        if (info != null) {
            info.running = false;
            activeTasks.remove(taskId);
        }

        if (taskId.equals(this.currentTaskId)) {
            this.currentTaskId = null;
            this.currentOwner = null;
            this.currentMenu = null;
            activeCount.decrementAndGet();
            if (activeCount.get() < 0) activeCount.set(0);
            log.info("🔓 전역 락 해제 완료 (taskId={})", taskId);
        }
    }

    // ===============================================================
    // ✅ 현재 락 보유자 확인
    // ===============================================================
    public synchronized Optional<GlobalTaskInfo> getCurrentTaskInfo() {
        if (currentTaskId == null) return Optional.empty();
        return Optional.ofNullable(activeTasks.get(currentTaskId));
    }

    // ===============================================================
    // ✅ 현재 락 여부
    // ===============================================================
    public boolean isLocked() {
        return activeCount.get() > 0;
    }

    // ===============================================================
    // ✅ 전체 Task 목록 조회 (대시보드용)
    // ===============================================================
    public Map<String, GlobalTaskInfo> getActiveTasks() {
        return Collections.unmodifiableMap(activeTasks);
    }

    // ===============================================================
    // ✅ 관리자 강제 초기화 (비정상 상태 복구용)
    // ===============================================================
    public synchronized void forceReset() {
        activeTasks.clear();
        activeCount.set(0);
        currentMenu = null;
        currentOwner = null;
        currentTaskId = null;
        log.warn("⚠️ GlobalStockService 강제 초기화됨 (관리자 명령)");
    }

    // ===============================================================
    // ✅ 강제 완료 처리 (GlobalDashboardService에서 사용)
    // ===============================================================
    public synchronized void completeTask(String taskId) {
        GlobalTaskInfo info = activeTasks.get(taskId);
        if (info != null) {
            info.running = false;
            log.info("✅ 작업 완료 처리됨: {}", taskId);
            releaseLock(taskId);
        }
    }

    // ===============================================================
    // ✅ 잔류 락 자동 정리 (프로세스가 실제 없음)
    // ===============================================================
    public synchronized void forceUnlockIfNoProcess() {
        if (!isLocked()) return;

        // running=false 또는 activeTasks 비었는데 count 남아있는 경우
        boolean hasRunning = activeTasks.values().stream().anyMatch(t -> t.running);
        if (!hasRunning) {
            log.warn("🧹 잔류 락 자동 해제 (프로세스 없음, owner={})", currentOwner);
            activeTasks.clear();
            activeCount.set(0);
            currentMenu = null;
            currentOwner = null;
            currentTaskId = null;
        }
    }

    // ===============================================================
    // ✅ 즉시 강제 해제 (취소 직후 사용)
    // ===============================================================
    public synchronized void unlockForce() {
        if (!isLocked()) return;
        log.warn("🟥 즉시 강제 락 해제 실행 (owner={})", currentOwner);
        activeTasks.clear();
        activeCount.set(0);
        currentMenu = null;
        currentOwner = null;
        currentTaskId = null;
    }

    // ===============================================================
    // ✅ 현재 락 상태 텍스트로 반환 (디버그용)
    // ===============================================================
    public String debugStatus() {
        return String.format("[LOCK=%s] owner=%s, menu=%s, activeCount=%d",
                (isLocked() ? "ON" : "OFF"),
                currentOwner,
                currentMenu,
                activeCount.get());
    }
}
