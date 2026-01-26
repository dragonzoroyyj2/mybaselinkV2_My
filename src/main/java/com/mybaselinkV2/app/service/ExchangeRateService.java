package com.mybaselinkV2.app.service;

import java.util.HashMap;
import java.util.Map;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.text.DecimalFormat; // 추가

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 자바 기반 실시간 환율 정보 서비스
 */
@Service
public class ExchangeRateService {

    private final String API_URL = "https://open.er-api.com/v6/latest/KRW";
    
    private Map<String, Object> exchangeData = new HashMap<>(); 
    private long lastUpdateTimeMillis = 0;
    
    // ✅ 천단위 콤마 포맷터 추가
    private final DecimalFormat df = new DecimalFormat("#,###");

    /**
     * 레이아웃 버블링(중복 호출)으로 인한 로그 도배 방지 로직 포함
     */
    public Map<String, Object> getExchangeRates() {
        long currentTime = System.currentTimeMillis();

        // 1초 이내의 중복 요청은 API 업데이트를 건너뜀 (로그 단일화 및 성능 최적화)
        if (exchangeData.isEmpty() || (currentTime - lastUpdateTimeMillis > 1000)) {
            updateExchangeRates();
        }
        
        return exchangeData;
    }

    private void updateExchangeRates() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(API_URL, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            JsonNode rates = root.path("rates");

            // 🔥 [수정] KRW 기준 API이므로 1 / rate를 통해 1외화당 원화 가격으로 역산합니다.
            // 일본 엔화(JPY)의 경우 보통 100엔 기준이므로 100을 곱해줍니다.
            double usdRate = rates.path("USD").asDouble();
            double jpyRate = rates.path("JPY").asDouble();
            double eurRate = rates.path("EUR").asDouble();

            if (usdRate != 0) exchangeData.put("USD", df.format(Math.round(1 / usdRate)));
            if (jpyRate != 0) exchangeData.put("JPY", df.format(Math.round(100 / jpyRate))); // 100엔 기준
            if (eurRate != 0) exchangeData.put("EUR", df.format(Math.round(1 / eurRate)));

            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            exchangeData.put("updateTime", now);

            lastUpdateTimeMillis = System.currentTimeMillis();
            System.out.println("환율 정보가 업데이트되었습니다. (역산 적용 완료)"); 

        } catch (Exception e) {
            System.out.println("환율 정보를 가져오는 중 오류가 발생했습니다.");
            e.printStackTrace();
        }
    }

    public Map<String, Object> getLatest() {
        return getExchangeRates();
    }
}