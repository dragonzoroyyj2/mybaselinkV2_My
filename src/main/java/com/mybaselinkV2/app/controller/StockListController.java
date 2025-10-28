package com.mybaselinkV2.app.controller;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselinkV2.app.service.StockListService;

@RestController
@RequestMapping("/api/stock")
public class StockListController {

    private final StockListService service;

    public StockListController(StockListService service) {
        this.service = service;
    }

    // =====================================
    // 🔍 리스트 조회 (검색 + 페이징)
    // =====================================
    @GetMapping("/list")
    public Map<String, Object> getStockList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,
            @RequestParam(defaultValue = "true") boolean pagination,
            @RequestParam(defaultValue = "web") String env
    ) {
        try {
            List<Map<String, Object>> all = service.getStockList();
            List<Map<String, Object>> filtered = new ArrayList<>(all);

            // 검색어 필터: 환경 구분 없이 모든 필드 검색
            if (search != null && !search.isBlank()) {
                String s = search.toLowerCase(Locale.ROOT);
                filtered = filtered.stream()
                        .filter(item ->
                                safeStr(item.get("Code")).toLowerCase().contains(s) ||
                                safeStr(item.get("Name")).toLowerCase().contains(s) ||
                                safeStr(item.get("Dept")).toLowerCase().contains(s) ||
                                safeStr(item.get("Market")).toLowerCase().contains(s)
                        )
                        .collect(Collectors.toList());
            }

            Map<String, Object> result = new HashMap<>();

            // ✅ 클라이언트 모드 or 페이징 비활성화
            if (!pagination || "client".equalsIgnoreCase(mode)) {
                result.put("content", filtered);
                result.put("page", 0);
                result.put("totalPages", 1);
                result.put("totalElements", filtered.size());
                return result;
            }

            // ✅ 서버모드 페이징
            int totalElements = filtered.size();
            int totalPages = (int) Math.ceil((double) totalElements / size);
            int start = page * size;
            int end = Math.min(start + size, totalElements);
            List<Map<String, Object>> paged = filtered.subList(Math.min(start, end), end);

            result.put("content", paged);
            result.put("page", page);
            result.put("totalPages", totalPages);
            result.put("totalElements", totalElements);

            return result;

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
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("주식리스트");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // 헤더 작성
            String[] headers = {"종목코드", "회사명", "시장", "업종", "종가", "시가", "고가", "저가", "거래량", "기준일"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 필터링
            List<Map<String, Object>> filtered = service.getStockList().stream()
                    .filter(item -> search == null || search.isBlank()
                            || safeStr(item.get("Name")).contains(search)
                            || safeStr(item.get("Code")).contains(search)
                            || safeStr(item.get("Dept")).contains(search))
                    .collect(Collectors.toList());

            // 데이터 행 작성
            int rowIdx = 1;
            for (Map<String, Object> item : filtered) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safeStr(item.get("Code")));
                row.createCell(1).setCellValue(safeStr(item.get("Name")));
                row.createCell(2).setCellValue(safeStr(item.get("Market")));
                row.createCell(3).setCellValue(safeStr(item.get("Dept")));
                row.createCell(4).setCellValue(safeStr(item.get("Close")));
                row.createCell(5).setCellValue(safeStr(item.get("Open")));
                row.createCell(6).setCellValue(safeStr(item.get("High")));
                row.createCell(7).setCellValue(safeStr(item.get("Low")));
                row.createCell(8).setCellValue(safeStr(item.get("Volume")));
                row.createCell(9).setCellValue(safeStr(item.get("Date")));
            }

            // 자동 열 너비 조정
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            // 워크북 → 바이트 변환
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            byte[] bytes = out.toByteArray();

            // 파일명 인코딩
            String filename = "주식리스트_" + LocalDate.now() + ".xlsx";
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            String contentDisposition = "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .header(HttpHeaders.CONTENT_TYPE,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=UTF-8")
                    .body(bytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    .body(("엑셀 생성 실패: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // =====================================
    // 🔹 유틸: null 안전 문자열 변환
    // =====================================
    private String safeStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}