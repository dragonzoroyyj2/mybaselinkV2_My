package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.P01A05Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/p01a05List")
public class P01A05ApiController {

    private final P01A05Service service;

    public P01A05ApiController(P01A05Service service) {
        this.service = service;
    }

    /** 🔍 리스트 조회 */
    @GetMapping
    public Map<String, Object> getList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "server") String mode,
            @RequestParam(defaultValue = "true") boolean pagination
    ) {
        return service.getList(page, size, search, mode, pagination);
    }

    /** 🔎 단건 조회 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDetail(@PathVariable int id) {
        return service.getDetail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "데이터 없음")));
    }

    /** ➕ 등록 */
    @PostMapping
    public Map<String, Object> addItem(@RequestBody Map<String, Object> request) {
        return service.addItem(request);
    }

    /** ✏️ 수정 */
    @PutMapping("/{id}")
    public Map<String, Object> updateItem(@PathVariable int id, @RequestBody Map<String, Object> request) {
        return service.updateItem(id, request);
    }

    /** ❌ 삭제 */
    @DeleteMapping
    public Map<String, Object> deleteItems(@RequestBody List<Integer> ids) {
        return service.deleteItems(ids);
    }

    /** 📊 엑셀 다운로드 */
    @GetMapping("/excel")
    public ResponseEntity<byte[]> downloadExcel(@RequestParam(required = false) String search) {
        return service.downloadExcel(search);
    }
}
