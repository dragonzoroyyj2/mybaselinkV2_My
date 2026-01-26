package com.mybaselinkV2.app.service;

import java.util.HashMap;
import java.util.Map;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.text.DecimalFormat;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 나스닥, 다우, 코스피, 코스닥 지수 정보를 제공하는 서비스
 * Yahoo Finance를 기본으로 사용하며, API 차단을 방지하기 위해 User-Agent 설정 및 캐싱 전략을 강화합니다.
 */
@Service
public class StockIndexService {

    // ✅ 멀티스레드 안전한 ConcurrentHashMap 사용
    private final Map<String, Object> stockData = new ConcurrentHashMap<>();
    private long lastUpdateTimeMillis = 0;
    private final DecimalFormat df = new DecimalFormat("#,###.##");
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ 캐시 유지 시간 (10분으로 설정하여 Yahoo API 429 차단 방지)
    private static final long CACHE_DURATION = 600000;

    /**
     * 실시간 지수 정보를 반환 (캐싱 적용)
     */
    public Map<String, Object> getStockIndices() {
        long currentTime = System.currentTimeMillis();

        // 데이터가 없거나 10분이 지난 경우에만 외부 API 호출
        if (stockData.isEmpty() || (currentTime - lastUpdateTimeMillis > CACHE_DURATION)) {
            updateStockIndices();
        }
        return stockData;
    }

    /**
     * 외부 금융 API를 통해 실제 데이터를 업데이트
     */
    private void updateStockIndices() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            
            // ✅ 실시간성이 높고 지수 데이터를 안정적으로 제공하는 Yahoo Finance로 통일
            // 공공데이터포털(금융위) API는 지수용이 아니므로 제거하고 야후로 대체함
            updateYahooIndex(restTemplate, "^IXIC", "NASDAQ");
            updateYahooIndex(restTemplate, "^DJI", "DOW");
            updateYahooIndex(restTemplate, "^KS11", "KOSPI");
            updateYahooIndex(restTemplate, "^KQ11", "KOSDAQ");

            // 업데이트 시간 기록
            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            stockData.put("updateTime", now);

            lastUpdateTimeMillis = System.currentTimeMillis();
            System.out.println("금융 지표 업데이트 완료: " + now);
            
        } catch (Exception e) {
            System.err.println("지수 업데이트 중 오류 발생: " + e.getMessage());
            if(stockData.isEmpty()) {
                setDefaultValues();
            }
        }
    }

    /**
     * Yahoo Finance API 호출 로직 (헤더 추가로 429 에러 방어)
     */
    private void updateYahooIndex(RestTemplate restTemplate, String symbol, String key) {
        try {
            // Yahoo API 경로
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?interval=1m&range=1d";
            
            // ✅ [수정] 브라우저처럼 보이게 헤더 추가 (429 Too Many Requests 방지 핵심)
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String response = responseEntity.getBody();

            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.path("chart").path("result").get(0);
            
            if (result != null && !result.isMissingNode()) {
                JsonNode meta = result.path("meta");
                double currentPrice = meta.path("regularMarketPrice").asDouble();
                double previousClose = meta.path("chartPreviousClose").asDouble();
                
                stockData.put(key, df.format(currentPrice));
                stockData.put(key + "_TYPE", (currentPrice >= previousClose) ? "up" : "down");
            }
        } catch (Exception e) {
            System.err.println("Yahoo API 호출 실패 (" + symbol + "): " + e.getMessage());
            // 실패 시 기존 데이터가 없으면 '연결지연' 표시
            if (!stockData.containsKey(key)) {
                stockData.put(key, "연결지연");
                stockData.put(key + "_TYPE", "up");
            }
        }
    }

    /**
     * 장애 시 노출될 초기/기본값
     */
    private void setDefaultValues() {
        stockData.put("NASDAQ", "16,000"); stockData.put("NASDAQ_TYPE", "up");
        stockData.put("DOW", "38,000"); stockData.put("DOW_TYPE", "up");
        stockData.put("KOSPI", "2,500"); stockData.put("KOSPI_TYPE", "down");
        stockData.put("KOSDAQ", "850"); stockData.put("KOSDAQ_TYPE", "up");
        stockData.put("updateTime", "점검중");
    }
}