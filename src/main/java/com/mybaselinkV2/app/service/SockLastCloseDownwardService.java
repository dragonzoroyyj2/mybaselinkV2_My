package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Python 스크립트를 호출하여 연속 하락 종목 조회 및 차트 반환 서비스 (비동기 처리)
 */
@Service
public class SockLastCloseDownwardService {

    private static final Logger logger = LoggerFactory.getLogger(SockLastCloseDownwardService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStatusService taskStatusService;

    // Python 실행 환경
    // ✅ @Value 어노테이션으로 프로퍼티 값 주입
    @Value("${python.executable.path:}")
    private String pythonExe;
    
 // Python 스크립트 경로
    @Value("${python.find_last_close_downward.path:}")
    private String scriptPath;
    
    @Value("${python.working.dir:}")
    private String pythonWorkingDir;
    
    // 인메모리 락 (단일 서버 환경용)
    private final AtomicBoolean pythonScriptLock = new AtomicBoolean(false);

    public SockLastCloseDownwardService(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    /**
     * 비동기 작업 시작: 연속 하락 종목 조회
     * @param taskId 작업 ID
     */
    @Async
    public void startLastCloseDownwardTask(String taskId, String start, String end, int topN) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        if (pythonScriptLock.compareAndSet(false, true)) { // 락 획득 시도
            try {
                List<Map<String, Object>> results = getCachedLastCloseDownward(start, end, topN);
                //taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", results, null));
            } catch (Exception e) {
                String errorMsg = "비동기 작업 처리 중 오류: " + e.getMessage();
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
                logger.error("비동기 작업 실패: taskId={}", taskId, e);
            } finally {
                pythonScriptLock.set(false); // 락 해제
            }
        } else {
            String errorMsg = "작업 잠금 획득 실패 (다른 작업 진행 중)";
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.warn(errorMsg);
        }
    }

    /**
     * 비동기 작업 시작: 개별 종목 차트
     * @param taskId 작업 ID
     */
    @Async
    public void startFetchChartTask(String taskId, String baseSymbol, String start, String end) {
        taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("IN_PROGRESS", null, null));
        if (pythonScriptLock.compareAndSet(false, true)) { // 락 획득 시도
            try {
                String base64Image = fetchChart(baseSymbol, start, end);
                
                // HashMap을 사용하여 null이 가능한 Map 생성
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("image_data", base64Image);
                
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("COMPLETED", resultMap, null));
            } catch (Exception e) {
                String errorMsg = "비동기 작업 처리 중 오류: " + e.getMessage();
                taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
                logger.error("비동기 작업 실패: taskId={}", taskId, e);
            } finally {
                pythonScriptLock.set(false); // 락 해제
            }
        } else {
            String errorMsg = "작업 잠금 획득 실패 (다른 작업 진행 중)";
            taskStatusService.setTaskStatus(taskId, new TaskStatusService.TaskStatus("FAILED", null, errorMsg));
            logger.warn(errorMsg);
        }
    }

    @Cacheable(value = "lastCloseDownwardCache", key = "#start + '-' + #end + '-' + #topN", sync = true)
    public List<Map<String, Object>> getCachedLastCloseDownward(String start, String end, int topN) {
        logger.info("캐시 조회 또는 실행: lastCloseDownwardCache, key='{}'", start + "-" + end + "-" + topN);
        return executePythonForDownwardList(start, end, topN);
    }

    @Cacheable(value = "chartImageCache", key = "#baseSymbol + '-' + #start + '-' + #end", sync = true)
    public String fetchChart(String baseSymbol, String start, String end) {
        return executePythonForChart(baseSymbol, start, end);
    }

    // Python 호출 로직 (연속 하락 종목 리스트)
    private List<Map<String, Object>> executePythonForDownwardList(String start, String end, int topN) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", "ALL",
                    "--start_date", start,
                    "--end_date", end,
                    "--topN", String.valueOf(topN)
            };
            logger.info("Python 스크립트 실행 시작: 연속 하락 종목 조회");

            JsonNode pythonResult = executePythonScript(command);
            
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 실행 오류: {}", errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                List<Map<String, Object>> results = mapper.convertValue(
                        pythonResult,
                        new TypeReference<List<Map<String, Object>>>() {}
                );
                logger.info("Python 스크립트 실행 완료: 연속 하락 종목 조회 (총 {}건)", results.size());
                return results;
            } else {
                logger.error("Python 스크립트 결과가 null입니다.");
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            logger.error("Python 스크립트 호출 실패", e);
            throw new RuntimeException("Python 스크립트 호출 실패: " + e.getMessage());
        }
    }

    // Python 호출 로직 (차트 생성)
    private String executePythonForChart(String baseSymbol, String start, String end) {
        try {
            String[] command = {
                    pythonExe, "-u", scriptPath,
                    "--base_symbol", baseSymbol,
                    "--start_date", start,
                    "--end_date", end,
                    "--chart"
            };
            logger.info("종목 {} 차트 생성 시작. 기간: {} ~ {}", baseSymbol, start, end);

            JsonNode pythonResult = executePythonScript(command);
            
            if (pythonResult != null) {
                if (pythonResult.has("error")) {
                    String errorMsg = pythonResult.get("error").asText();
                    logger.error("Python 스크립트 차트 생성 오류 ({}): {}", baseSymbol, errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                // image_data가 없을 경우 null을 반환
                JsonNode imageDataNode = pythonResult.get("image_data");
                if (imageDataNode == null || imageDataNode.isNull()) {
                    return null;
                }
                String imageData = imageDataNode.asText();
                logger.info("종목 {} 차트 생성 완료.", baseSymbol);
                return imageData;
            } else {
                logger.error("Python 스크립트 결과가 null입니다.");
                throw new RuntimeException("Python 스크립트 결과가 null입니다.");
            }
        } catch (Exception e) {
            logger.error("차트 생성 실패 ({}).", baseSymbol, e);
            throw new RuntimeException("차트 생성 실패: " + e.getMessage());
        }
    }

    // Python 스크립트 실행 및 JSON 파싱
    private JsonNode executePythonScript(String[] command)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {

        File scriptDir = new File(pythonWorkingDir);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(scriptDir);
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = pb.start();

        ExecutorService errorExecutor = Executors.newSingleThreadExecutor();
        Future<?> errorFuture = errorExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> logger.error("[Python ERR] {}", line));
            } catch (IOException e) {
                logger.error("Error reading Python stderr stream", e);
            }
        });

        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        Future<String> outputFuture = outputExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                logger.error("Error reading Python stdout stream", e);
                return "";
            }
        });

        String pythonOutput = null;
        try {
            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("Python 프로세스가 시간 내 종료되지 않았습니다.");
            }

            pythonOutput = outputFuture.get();

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python 스크립트 종료 코드: " + exitCode + ". Python 출력: " + pythonOutput);
            }
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            errorExecutor.shutdown();
            outputExecutor.shutdown();
        }
        
        if (pythonOutput == null || pythonOutput.trim().isEmpty()){
            logger.warn("파이썬 스크립트가 빈 문자열을 반환했습니다. 빈 JSON 배열로 처리합니다.");
            return mapper.readTree("[]");
        }
        
        try {
            return mapper.readTree(pythonOutput);
        } catch (IOException e) {
            logger.error("JSON 파싱 중 오류 발생. 파이썬 출력: {}", pythonOutput, e);
            throw new RuntimeException("파이썬 스크립트의 JSON 결과를 파싱할 수 없습니다.");
        }
    }
}
