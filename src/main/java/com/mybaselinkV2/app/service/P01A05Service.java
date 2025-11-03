package com.mybaselinkV2.app.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class P01A05Service {

    private final List<Map<String, Object>> mockList = new ArrayList<>();

    public P01A05Service() {
        // ✅ 초기 Mock 데이터 (7개 항목 전부 포함)
        for (int i = 1; i <= 600; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", i);
            item.put("title", "보고서 " + i);
            item.put("owner", "홍길동");
            item.put("regDate", "2025-10-06");
            item.put("serverStatus", (i % 2 == 0) ? "정상" : "점검중");
            item.put("featureOption", (i % 3 == 0) ? "확장모드" : "일반");
            item.put("remark", "테스트 비고 " + i);
            mockList.add(item);
        }
    }

    /** ✅ 리스트 조회 (검색 + 페이징 + 정렬) */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        List<Map<String, Object>> filtered = new ArrayList<>(mockList);

        // 🔍 검색 필터링
        if (search != null && !search.isEmpty()) {
            String s = search.toLowerCase();
            filtered.removeIf(item ->
                    !safeStr(item.get("title")).toLowerCase().contains(s)
                    && !safeStr(item.get("owner")).toLowerCase().contains(s)
                    && !safeStr(item.get("serverStatus")).toLowerCase().contains(s)
                    && !safeStr(item.get("featureOption")).toLowerCase().contains(s)
                    && !safeStr(item.get("remark")).toLowerCase().contains(s)
            );
        }

        // ✅ 최신 등록순 정렬 (id 내림차순)
        filtered.sort((a, b) -> Integer.compare((int) b.get("id"), (int) a.get("id")));

        Map<String, Object> result = new HashMap<>();

        if (!pagination || "client".equalsIgnoreCase(mode)) {
            result.put("content", filtered);
            result.put("page", 0);
            result.put("totalPages", 1);
            result.put("totalElements", filtered.size());
            return result;
        }

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

    /** ✅ 단건 상세 조회 */
    public Optional<Map<String, Object>> getDetail(int id) {
        return mockList.stream().filter(m -> (int) m.get("id") == id).findFirst();
    }

    /** ✅ 등록 (7개 필드 전부 포함) */
    public Map<String, Object> addItem(Map<String, Object> req) {
        int newId = mockList.stream().mapToInt(m -> (int) m.get("id")).max().orElse(0) + 1;
        Map<String, Object> item = new HashMap<>();
        item.put("id", newId);
        item.put("title", safeStr(req.get("title")));
        item.put("owner", safeStr(req.get("owner")));
        item.put("regDate", safeStr(req.getOrDefault("regDate", LocalDate.now().toString())));
        item.put("serverStatus", safeStr(req.getOrDefault("serverStatus", "정상")));
        item.put("featureOption", safeStr(req.getOrDefault("featureOption", "일반")));
        item.put("remark", safeStr(req.getOrDefault("remark", "")));
        mockList.add(item);
        return Map.of("status", "success", "id", newId);
    }

    /** ✅ 수정 (7개 항목 전부 갱신) */
    public Map<String, Object> updateItem(int id, Map<String, Object> req) {
        Optional<Map<String, Object>> found = getDetail(id);
        if (found.isPresent()) {
            Map<String, Object> item = found.get();
            item.put("title", safeStr(req.get("title")));
            item.put("owner", safeStr(req.get("owner")));
            item.put("regDate", safeStr(req.get("regDate")));
            item.put("serverStatus", safeStr(req.get("serverStatus")));
            item.put("featureOption", safeStr(req.get("featureOption")));
            item.put("remark", safeStr(req.get("remark")));
            return Map.of("status", "updated");
        }
        return Map.of("status", "not_found");
    }

    /** ✅ 삭제 (다중) */
    public Map<String, Object> deleteItems(List<Integer> ids) {
        mockList.removeIf(m -> ids.contains(m.get("id")));
        return Map.of("status", "deleted", "count", ids.size());
    }

    /** ✅ 엑셀 다운로드 (7개 항목 전부 포함) */
    public ResponseEntity<byte[]> downloadExcel(String search) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("리스트");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            String[] headers = {"ID", "제목", "작성자", "등록일", "서버상태", "기능옵션", "비고"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            List<Map<String, Object>> filtered = mockList.stream()
                    .filter(item -> search == null || search.isBlank()
                            || safeStr(item.get("title")).contains(search)
                            || safeStr(item.get("owner")).contains(search)
                            || safeStr(item.get("serverStatus")).contains(search)
                            || safeStr(item.get("featureOption")).contains(search)
                            || safeStr(item.get("remark")).contains(search))
                    .sorted((a, b) -> Integer.compare((int) b.get("id"), (int) a.get("id")))
                    .collect(Collectors.toList());

            int rowIdx = 1;
            for (Map<String, Object> item : filtered) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safeStr(item.get("id")));
                row.createCell(1).setCellValue(safeStr(item.get("title")));
                row.createCell(2).setCellValue(safeStr(item.get("owner")));
                row.createCell(3).setCellValue(safeStr(item.get("regDate")));
                row.createCell(4).setCellValue(safeStr(item.get("serverStatus")));
                row.createCell(5).setCellValue(safeStr(item.get("featureOption")));
                row.createCell(6).setCellValue(safeStr(item.get("remark")));
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            byte[] bytes = out.toByteArray();

            String filename = "리스트_" + LocalDate.now() + ".xlsx";
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
                    .body(("엑셀 생성 중 오류 발생: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String safeStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
