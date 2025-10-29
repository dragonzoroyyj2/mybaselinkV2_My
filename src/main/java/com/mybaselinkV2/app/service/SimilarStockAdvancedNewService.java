package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class SimilarStockAdvancedNewService {

    private final ObjectMapper mapper = new ObjectMapper();

    private final String pythonExe = "C:\\Users\\dragon\\AppData\\Local\\Programs\\Python\\Python310\\python.exe";
    private final String scriptPath = "D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python\\find_similar_new_full.py";
    
    public List<Map<String, Object>> fetchSimilar(String companyCode, String start, String end, int nSimilarStocks, String method) {
        try {
            String[] command = {
                    pythonExe,
                    "-u",
                    scriptPath,
                    "--base_symbol", companyCode,
                    "--start_date", start,
                    "--end_date", end,
                    "--n_similar", String.valueOf(nSimilarStocks),
                    "--method", method
            };
            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult.has("error")) {
                throw new RuntimeException(pythonResult.get("error").asText());
            }
            return mapper.convertValue(pythonResult.get("similar_stocks"), new TypeReference<>(){});
        } catch (Exception e) {
            throw new RuntimeException("Python 실행/통신 오류: " + e.getMessage(), e);
        }
    }

    public String fetchChart(String baseSymbol, String compareSymbol, String start, String end) {
        try {
            String[] command = {
                    pythonExe,
                    "-u",
                    scriptPath,
                    "--base_symbol", baseSymbol,
                    "--compare_symbol", compareSymbol,
                    "--start_date", start,
                    "--end_date", end
            };
            JsonNode pythonResult = executePythonScript(command);

            if (pythonResult.has("error")) {
                throw new RuntimeException(pythonResult.get("error").asText());
            }
            return pythonResult.get("image_data").asText();
        } catch (Exception e) {
            throw new RuntimeException("Python 차트 생성 오류: " + e.getMessage(), e);
        }
    }

    private JsonNode executePythonScript(String[] command) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(command);
        File scriptDir = new File("D:\\project\\dev_boot_project\\workspace\\MyBaseLink\\python");
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = pb.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> outputFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        });

        Future<Void> errorFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> System.err.println("[Python ERR] " + line));
            }
            return null;
        });

        String pythonOutput = outputFuture.get(180, TimeUnit.SECONDS);
        errorFuture.get(180, TimeUnit.SECONDS);

        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("Python 프로세스가 시간 내 종료되지 않았습니다.");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String msg = "Python 실행 실패 (exitCode=" + exitCode + ")";
            if (pythonOutput != null && !pythonOutput.isBlank()) {
                msg += ": " + pythonOutput.split("\n")[0];
            }
            throw new RuntimeException(msg);
        }

        if (pythonOutput == null || pythonOutput.isBlank()) {
            throw new RuntimeException("Python 출력이 없습니다.");
        }

        System.out.println(pythonOutput); // 로그
        executor.shutdown();
        return mapper.readTree(pythonOutput);
    }
}