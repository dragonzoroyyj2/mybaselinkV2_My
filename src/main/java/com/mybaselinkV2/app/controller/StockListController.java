package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ✅ StockListController (조회 전용)
 * --------------------------------------------------------
 * - JSON 기반 주식리스트 조회 + 엑셀 다운로드
 * - 등록 / 수정 / 삭제 없음
 * --------------------------------------------------------
 */
@RestController
@RequestMapping("/api/stockList")
public class StockListController {

    private final StockListService service;

    public StockListController(StockListService service) {
        this.service = service;
    }

    // =====================================
    // 🔍 리스트 조회 (검색 + 페이징)
    // =====================================
    @GetMapping("/list")
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,
            @RequestParam(defaultValue = "true") boolean pagination
    ) {
        try {
            return service.getList(page, size, search, mode, pagination);
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", "데이터 조회 실패: " + e.getMessage());
        }
    }

    // =====================================
    // 📊 엑셀(XLSX) 다운로드
    // =====================================
    @GetMapping("/excel")
    public ResponseEntity<byte[]> downloadExcel(@RequestParam(required = false) String search) {
        try {
            return service.downloadExcel(search);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(("엑셀 생성 실패: " + e.getMessage()).getBytes());
        }
    }
}
