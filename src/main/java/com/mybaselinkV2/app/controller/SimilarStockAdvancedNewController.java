package com.mybaselinkV2.app.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselinkV2.app.service.SimilarStockAdvancedNewService;


@RestController
@RequestMapping("/api/krx")
public class SimilarStockAdvancedNewController {

    private final SimilarStockAdvancedNewService service;

    public SimilarStockAdvancedNewController(SimilarStockAdvancedNewService service) {
        this.service = service;
    }

    // 유사 종목 분석
    @GetMapping("/similar-advanced-new")
    public ResponseEntity<Map<String, Object>> getSimilarStocks(
            @RequestParam String companyCode,
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "10") int nSimilarStocks,
            @RequestParam(defaultValue = "cosine") String method
    ) {
        try {
            List<Map<String,Object>> results = service.fetchSimilar(companyCode, start, end, nSimilarStocks, method);
            return ResponseEntity.ok(Map.of(
                    "base_symbol", companyCode,
                    "similar_stocks", results
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 개별 종목 차트
    @GetMapping("/similar-advanced-new/chart")
    public ResponseEntity<Map<String, Object>> getChart(
            @RequestParam String baseSymbol,
            @RequestParam String compareSymbol,
            @RequestParam String start,
            @RequestParam String end
    ) {
        try {
            String base64Image = service.fetchChart(baseSymbol, compareSymbol, start, end);
            if (base64Image != null) {
                return ResponseEntity.ok(Map.of("image_data", base64Image));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "차트 생성 실패"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "차트 조회 중 오류 발생: " + e.getMessage()));
        }
    }
}