package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.dto.PythonScriptFile;
import com.mybaselinkV2.app.service.SyFileStockPythonProdService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

/**
 * ===============================================================
 * 📁 SyFileStockPythonProdController (리빌드 완전체)
 * ---------------------------------------------------------------
 * ✔ 화면 호출 (syFileStockPythonProd.html)
 * ✔ Python 스크립트 목록 조회
 * ✔ 파일 업로드
 * ✔ 존재 여부 체크
 * ✔ 단일 실행 / 단일 삭제
 * ✔ 일괄 실행 / 삭제 / 배포
 * ---------------------------------------------------------------
 * 🔒 모든 /api/python/** 는 JWT 인증필수 (SecurityConfig)
 * ===============================================================
 */
@Controller
public class SyFileStockPythonProdController {

    private final SyFileStockPythonProdService service;

    public SyFileStockPythonProdController(SyFileStockPythonProdService service) {
        this.service = service;
    }

    /**
     * ===============================================================
     * 📌 화면 렌더링
     * ===============================================================
     */
    @GetMapping("/syFileStockPythonProd")
    public String index(Model model) {
        return "syFileStockPythonProd";
    }

    /**
     * ===============================================================
     * 📌 Python 스크립트 목록 조회
     * ===============================================================
     */
    @GetMapping("/api/python/list")
    @ResponseBody
    public List<PythonScriptFile> list() {
        return service.listPythonFiles();
    }

    /**
     * ===============================================================
     * 📌 Python 파일 업로드
     * ===============================================================
     */
    @PostMapping("/api/python/upload")
    @ResponseBody
    public ResponseEntity<String> upload(@RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body("업로드할 파일이 없습니다.");
        }

        int count = service.saveFiles(files);
        return ResponseEntity.ok(count + "개의 파일이 성공적으로 업로드되었습니다.");
    }

    /**
     * ===============================================================
     * 📌 업로드 전 – 파일 존재 여부 체크
     * ===============================================================
     */
    @PostMapping("/api/python/check-existence")
    @ResponseBody
    public ResponseEntity<List<String>> checkExistence(@RequestBody List<String> filenames) {

        if (filenames == null || filenames.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<String> found = service.checkExistingFiles(filenames);
        return ResponseEntity.ok(found);
    }

    /**
     * ===============================================================
     * 📌 단일 실행 (Stub)
     * ===============================================================
     */
    @PostMapping("/api/python/run/{filename}")
    @ResponseBody
    public ResponseEntity<String> run(@PathVariable String filename) {

        boolean ok = service.runScript(filename);

        if (ok) {
            return ResponseEntity.ok(filename + " 실행 요청 완료");
        }

        return ResponseEntity.status(500)
                .body(filename + " 실행 요청 실패");
    }

    /**
     * ===============================================================
     * 📌 단일 삭제
     * ===============================================================
     */
    @DeleteMapping("/api/python/delete/{filename}")
    @ResponseBody
    public ResponseEntity<String> delete(@PathVariable String filename) {

        boolean ok = service.deleteFileSafe(filename);

        if (ok) {
            return ResponseEntity.ok(filename + " 삭제 완료");
        }

        return ResponseEntity.status(500)
                .body(filename + " 삭제 실패 (파일 없음 혹은 권한 오류)");
    }

    /**
     * ===============================================================
     * 📌 일괄 삭제
     * ===============================================================
     */
    @DeleteMapping("/api/python/batch-delete")
    @ResponseBody
    public ResponseEntity<String> deleteBatch(@RequestBody List<String> list) {

        if (list == null || list.isEmpty()) {
            return ResponseEntity.badRequest().body("삭제할 파일 목록이 필요합니다.");
        }

        int ok = service.deleteBatchFiles(list);

        if (ok == list.size()) {
            return ResponseEntity.ok(ok + "개 모두 삭제 완료");
        }

        return ResponseEntity.status(500)
                .body("일부 삭제 실패: 성공 " + ok + "/" + list.size());
    }

    /**
     * ===============================================================
     * 📌 일괄 실행 (Stub)
     * ===============================================================
     */
    @PostMapping("/api/python/batch-run")
    @ResponseBody
    public ResponseEntity<String> runBatch(@RequestBody List<String> list) {

        if (list == null || list.isEmpty()) {
            return ResponseEntity.badRequest().body("실행할 스크립트 목록이 필요합니다.");
        }

        int ok = service.runBatchScripts(list);

        if (ok == list.size()) {
            return ResponseEntity.ok(ok + "개 실행 요청 완료");
        }

        return ResponseEntity.status(500)
                .body("일부 실행 실패: 성공 " + ok + "/" + list.size());
    }

    /**
     * ===============================================================
     * 📌 일괄 배포 (Dev → 운영)
     * ===============================================================
     */
    @PostMapping("/api/python/batch-deploy")
    @ResponseBody
    public ResponseEntity<String> deploy(@RequestBody List<String> list) {

        if (list == null || list.isEmpty()) {
            return ResponseEntity.badRequest().body("배포할 파일 목록이 필요합니다.");
        }

        int ok = service.deployFiles(list);

        if (ok == list.size()) {
            return ResponseEntity.ok(ok + "개 파일이 성공적으로 배포되었습니다.");
        }

        if (ok == 0) {
            return ResponseEntity.status(500)
                    .body("배포 실패: Dev(클래스패스) 파일 없음");
        }

        return ResponseEntity.status(206)
                .body("일부만 배포됨: 성공 " + ok + "/" + list.size());
    }
}
