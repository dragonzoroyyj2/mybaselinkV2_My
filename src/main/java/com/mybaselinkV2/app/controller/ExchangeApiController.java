package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.ExchangeRateService;
import com.mybaselinkV2.app.service.StockIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

/**
 * 하단 바 비동기 업데이트를 위한 REST API 컨트롤러
 * 환율 정보와 주식 지수 정보를 통합하여 반환합니다.
 */
@RestController
@RequestMapping("/api/exchange")
public class ExchangeApiController {

    private final ExchangeRateService exchangeRateService;
    private final StockIndexService stockIndexService;

    @Autowired
    public ExchangeApiController(ExchangeRateService exchangeRateService, StockIndexService stockIndexService) {
        this.exchangeRateService = exchangeRateService;
        this.stockIndexService = stockIndexService;
    }

    /**
     * JavaScript에서 호출하는 최신 데이터 반환 API
     * 환율(USD)과 국내외 주요 지수 데이터를 하나의 Map으로 합쳐서 반환합니다.
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatestData() {
        Map<String, Object> combinedData = new HashMap<>();

        try {
            // 1. 환율 정보 가져오기 (USD 등)
            Map<String, Object> exchangeData = exchangeRateService.getLatest();
            if (exchangeData != null) {
                combinedData.putAll(exchangeData);
            }

            // 2. 주식 지수 정보 가져오기 (나스닥, 다우, 코스피, 코스닥)
            Map<String, Object> stockIndices = stockIndexService.getStockIndices();
            if (stockIndices != null) {
                combinedData.putAll(stockIndices);
            }
            
            // 만약 두 서비스의 updateTime이 다를 경우, 지수 정보의 시간을 우선적으로 사용하거나 
            // combinedData에 합쳐지는 과정에서 자연스럽게 최신 정보로 덮어씌워집니다.

        } catch (Exception e) {
            System.err.println("데이터 통합 업데이트 중 오류 발생: " + e.getMessage());
        }

        return combinedData;
    }
}