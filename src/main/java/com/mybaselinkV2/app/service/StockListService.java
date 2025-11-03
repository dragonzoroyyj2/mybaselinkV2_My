package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ StockListService (조회 전용 JSON 기반)
 * --------------------------------------------------------
 * - 파일 기반 데이터 조회 (stock_listing.json)
 * - 파일 없을 시 경고 반환
 * - 검색 + 페이징 + 엑셀 다운로드 전용
 * --------------------------------------------------------
 */
@Service
public class StockListService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${python.stock.stock_listing.path:}")
    private String localPath;

    /** ✅ JSON 파일 경로 탐색 */
    private File resolveJsonFile() {
        try {
            if (StringUtils.hasText(localPath)) {
                File file = new File(localPath);
                if (file.exists() && file.isFile()) {
                    return file;
                }
            }
            File defaultFile = new ClassPathResource("data/stock_listing.json").getFile();
            if (defaultFile.exists()) return defaultFile;
        } catch (Exception ignored) {
            // 경로 탐색 실패 시 무시
        }
        return null;
    }

    /** ✅ JSON 파일 읽기 */
    private List<Map<String, Object>> readJsonList() {
        try {
            File jsonFile = resolveJsonFile();
            if (jsonFile == null || !jsonFile.exists()) {
                System.err.println("⚠️ [경고] stock_listing.json 파일을 찾을 수 없습니다.");
                return Collections.emptyList();
            }
            List<Map<String, Object>> list =
                    mapper.readValue(jsonFile, new TypeReference<List<Map<String, Object>>>() {});
            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("id", i + 1);
            }
            return list;
        } catch (Exception e) {
            System.err.println("⚠️ JSON 파일 읽기 오류: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** ✅ 리스트 조회 (검색 + 페이징) */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        List<Map<String, Object>> all = readJsonList();

        if (all.isEmpty()) {
            return Map.of(
                    "content", Collections.emptyList(),
                    "page", 0,
                    "totalPages", 0,
                    "totalElements", 0,
                    "warning", "데이터 파일이 존재하지 않거나 비어 있습니다."
            );
        }

        List<Map<String, Object>> filtered = new ArrayList<>(all);

        // 🔍 검색 필터
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

        // ✅ 서버 모드 페이징 처리
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
    }

    /** ✅ 엑셀 다운로드 */
    public ResponseEntity<byte[]> downloadExcel(String search) {
        List<Map<String, Object>> data = readJsonList();
        if (data.isEmpty()) {
            byte[] msg = "⚠️ 데이터 파일이 존재하지 않거나 비어 있습니다.".getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(404)
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    .body(msg);
        }

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

            // 헤더 생성
            String[] headers = {"종목코드", "회사명", "시장", "업종", "종가", "시가", "고가", "저가", "거래량", "기준일"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 필터링
            List<Map<String, Object>> filtered = data.stream()
                    .filter(item -> search == null || search.isBlank()
                            || safeStr(item.get("Name")).contains(search)
                            || safeStr(item.get("Code")).contains(search)
                            || safeStr(item.get("Dept")).contains(search))
                    .collect(Collectors.toList());

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

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            byte[] bytes = out.toByteArray();

            String filename = "주식리스트_" + LocalDate.now() + ".xlsx";
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            String contentDisposition =
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename;

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

    private String safeStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
