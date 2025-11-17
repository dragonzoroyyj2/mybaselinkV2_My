package com.mybaselinkV2.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mybaselinkV2.app.dto.PythonScriptFile;

/**
 * =====================================================================
 * 📁 SyFileStockPythonProdService (최종 통합 수정 완료)
 * ---------------------------------------------------------------------
 * ✔ 운영 Python 파일 목록 조회 및 관리
 * ✔ **[최종 방어] 로컬 기동 시(prod profile ON이라도) 운영 파일/폴더 작업 원천 차단**
 * ✔ 모든 파일 작업은 application-prod.yml에 명시된 운영 경로에서만 수행
 * =====================================================================
 */
@Service
public class SyFileStockPythonProdService {

    private static final Logger log = LoggerFactory.getLogger(SyFileStockPythonProdService.class);

    @Value("${python.working.dir}")
    private String pythonWorkingDir;

    @Value("${python.backup.path}")
    private String pythonBackupDir;
    
    // 📌 [상수화]
    private static final int MAX_HISTORY_BACKUPS = 5;
    private static final String CLASSPATH_DIR = "python_scripts/";
    private static final String HISTORY_FOLDER = "individual_history";
    private static final String SNAPSHOT_PREFIX = "startup_snapshot_";
    private static final String LOG_PREFIX = "startup_log_";
    private static final String OPERATION_UPLOAD_PRE = "UPLOAD_PRE";
    private static final String OPERATION_DELETE_PRE = "DELETE_PRE";
    
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * =====================================================================
     * 📌 초기 폴더 생성 & classpath 기본 py 자동 복사 & 백업 정리
     * ---------------------------------------------------------------------
     * 🚨 [최종 방어] 로컬 기동 시 (빈 문자열 주입 시) 모든 초기화 작업 건너뛰기
     * =====================================================================
     */
    @Profile("prod")
    @PostConstruct
    public void init() {
        // 🚨 [로컬 환경 방어 로직] 주입된 값이 null이거나 비어있으면 초기화 작업 건너뛰기
        if (pythonBackupDir == null || pythonBackupDir.trim().isEmpty()) {
            log.warn("⚠️ Python 백업 경로가 설정되지 않았거나 비어있어 초기화 작업을 건너뜁니다. (로컬 환경 안전 보장)");
            return; 
        }
        
        try {
            Path workPath = Paths.get(pythonWorkingDir);
            Path backupPath = Paths.get(pythonBackupDir);

            // 1. 폴더 생성 (운영 경로에만 생성 시도)
            if (Files.notExists(workPath)) Files.createDirectories(workPath);
            if (Files.notExists(backupPath)) Files.createDirectories(backupPath);
            log.info("✅ Python 작업 폴더 준비 완료: {}", workPath.toAbsolutePath());
            log.info("✅ Python 백업 폴더 준비 완료: {}", backupPath.toAbsolutePath());

            // 2. Classpath 파일 복사 (Prod Working Dir로)
            Resource classpathDir = resolver.getResource("classpath:" + CLASSPATH_DIR);
            
            if (!classpathDir.exists()) {
                log.warn("⚠️ Classpath 리소스 폴더 ({})를 찾을 수 없습니다. 초기 복사를 건너킵니다.", CLASSPATH_DIR);
            } else {
                Resource[] resources = resolver.getResources("classpath:" + CLASSPATH_DIR + "*.py");

                for (Resource r : resources) {
                    if (!r.exists()) continue;

                    String filename = r.getFilename();
                    if (filename == null) continue;

                    Path target = workPath.resolve(filename);

                    if (Files.notExists(target)) {
                        try (InputStream in = r.getInputStream()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                            log.info("📦 Classpath 파일 복사: {}", filename);
                        }
                    }
                }
            }
            
            // 3. 오늘 일자 시작 로그 파일 생성 (운영 백업 경로 사용)
            createDailyLogBackup();

            // 4. WAS 시작 시점의 운영 스크립트 폴더 스냅샷 백업
            createStartupSnapshotBackup();

            // 5. WAS 시작 스냅샷 폴더와 로그 파일 정리
            cleanupOldBackups();
            
            // 6. 개별 작업 이력(individual_history) 폴더 정리
            cleanupIndividualHistory();

        } catch (IOException e) {
            log.error("❌ Python 초기화 중 치명적인 I/O 오류 발생. 애플리케이션 시작을 중단합니다.", e);
            throw new IllegalStateException("Python 파일 서비스 초기화 실패", e); 
        } catch (Exception e) {
            log.error("❌ Python 초기화 중 예상치 못한 오류 발생", e);
        }
    }
    
    // ---------------------------------------------------------------------
    // 📌 유틸리티: 디렉토리 재귀 삭제
    // ---------------------------------------------------------------------

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.notExists(path)) return;

        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()) 
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e); 
                        }
                    });
            } catch (UncheckedIOException e) {
                throw e.getCause(); 
            }
        } else {
            Files.delete(path);
        }
    }

    // ---------------------------------------------------------------------
    // 📌 개별 파일 작업 이력 폴더 정리
    // ---------------------------------------------------------------------

    private void cleanupIndividualHistory() {
        Path historyBasePath = Paths.get(pythonBackupDir).resolve(HISTORY_FOLDER);
        
        if (Files.notExists(historyBasePath)) {
            log.info("✨ 개별 백업 이력 폴더가 존재하지 않아 정리를 건너킵니다.");
            return;
        }

        try (Stream<Path> stream = Files.list(historyBasePath)) {
            List<Path> historyFolders = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(path -> {
                        try {
                            return Files.readAttributes(path, BasicFileAttributes.class).creationTime().toMillis();
                        } catch (IOException e) {
                            log.error("개별 이력 폴더 속성 읽기 오류: {}", path, e);
                            return Long.MAX_VALUE;
                        }
                    }))
                    .toList();
            
            int totalCount = historyFolders.size();
            
            if (totalCount > MAX_HISTORY_BACKUPS) {
                int toDelete = totalCount - MAX_HISTORY_BACKUPS;
                log.warn("🗑️ 개별 백업 이력 개수 초과. {}개 중 가장 오래된 항목 {}개를 삭제합니다.", totalCount, toDelete);
                
                for (int i = 0; i < toDelete; i++) {
                    Path folderToDelete = historyFolders.get(i);
                    
                    try {
                        deleteDirectoryRecursively(folderToDelete); 
                        log.info("✅ 개별 백업 이력 폴더 삭제 완료: {}", folderToDelete.getFileName());
                    } catch (IOException e) {
                        log.error("❌ 개별 백업 이력 폴더 삭제 실패: {}", folderToDelete, e);
                    }
                }
            } else {
                log.info("✨ 개별 백업 이력 개수 ({}개)는 제한({})을 초과하지 않아 정리를 건너깁니다.", totalCount, MAX_HISTORY_BACKUPS);
            }
            
        } catch (IOException e) {
            log.error("❌ 개별 백업 경로 접근 오류", e);
        }
    }


    // ---------------------------------------------------------------------
    // 📌 WAS 시작 스냅샷 폴더와 로그 파일을 모두 포함하여 개수 정리
    // ---------------------------------------------------------------------

    private void cleanupOldBackups() {
        Path backupBasePath = Paths.get(pythonBackupDir);
        
        try (Stream<Path> stream = Files.list(backupBasePath)) {
            List<Path> historyItems = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(SNAPSHOT_PREFIX) || name.startsWith(LOG_PREFIX);
                    })
                    .sorted(Comparator.comparingLong(path -> {
                        try {
                            return Files.readAttributes(path, BasicFileAttributes.class).creationTime().toMillis();
                        } catch (IOException e) {
                            log.error("폴더/파일 속성 읽기 오류: {}", path, e);
                            return Long.MAX_VALUE;
                        }
                    }))
                    .toList();
            
            int totalCount = historyItems.size();
            
            if (totalCount > MAX_HISTORY_BACKUPS) {
                int toDelete = totalCount - MAX_HISTORY_BACKUPS;
                log.warn("🗑️ WAS 백업 기록 개수 초과. {}개 중 가장 오래된 항목 {}개를 삭제합니다.", totalCount, toDelete);
                
                for (int i = 0; i < toDelete; i++) {
                    Path itemToDelete = historyItems.get(i);
                    String itemType = Files.isDirectory(itemToDelete) ? "스냅샷 폴더" : "로그 파일";

                    try {
                        deleteDirectoryRecursively(itemToDelete);
                        log.info("✅ {} 삭제 완료: {}", itemType, itemToDelete.getFileName());
                    } catch (IOException e) {
                        log.error("❌ {} 삭제 실패: {}", itemType, itemToDelete, e);
                    }
                }
            } else {
                log.info("✨ WAS 백업 기록 개수 ({}개)는 제한({})을 초과하지 않아 정리를 건너깁니다.", totalCount, MAX_HISTORY_BACKUPS);
            }
            
        } catch (IOException e) {
            log.error("❌ WAS 백업 경로 접근 오류", e);
        }
    }

    // ---------------------------------------------------------------------
    // 📌 WAS 시작 시점 스냅샷 백업
    // ---------------------------------------------------------------------

    private void createStartupSnapshotBackup() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        
        String backupDirName = SNAPSHOT_PREFIX + timestamp;
        
        Path workPath = Paths.get(pythonWorkingDir);
        Path backupBasePath = Paths.get(pythonBackupDir);
        Path backupSnapshotDir = backupBasePath.resolve(backupDirName);
        
        try {
            Files.createDirectories(backupSnapshotDir);
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(workPath, "*.py")) {
                int count = 0;
                for (Path source : stream) {
                    Path target = backupSnapshotDir.resolve(source.getFileName());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
                
                log.info("📸 WAS 시작 스냅샷 백업 성공: {} ({} files copied)", 
                                   backupSnapshotDir.toAbsolutePath(), count);
            }
            
        } catch (IOException e) {
            log.error("❌ WAS 시작 스냅샷 백업 실패", e);
        }
    }
    
    // ---------------------------------------------------------------------
    // 📌 오늘 일자 백업 로그 파일 생성
    // ---------------------------------------------------------------------

    private void createDailyLogBackup() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        
        String backupFileName = LOG_PREFIX + timestamp + ".txt"; 
        Path backupFilePath = Paths.get(pythonBackupDir).resolve(backupFileName);

        try {
            String content = String.format(
                "### Application Startup & Daily Log Backup Status ###\n" +
                "Date: %s\n" +
                "Service: SyFileStockPythonProdService\n" +
                "Status: SUCCESS\n" +
                "Note: This file confirms that the WAS initialized successfully and created the log file on the disk path.\n" +
                "Backup Path: %s\n" +
                "--- End of Log ---", 
                timestamp, backupFilePath.toAbsolutePath().toString());
            
            Files.writeString(backupFilePath, content, StandardCharsets.UTF_8);
            log.info("🎉 오늘일자 시작 로그 파일 생성 성공: {}", backupFilePath.toAbsolutePath());

        } catch (IOException e) {
            log.error("❌ 오늘일자 백업 로그 생성 실패", e);
        }
    }


    /**
     * =====================================================================
     * 📌 개별 파일 작업 이력 기록 (삭제/업로드 전 원본 백업)
     * =====================================================================
     */
    private boolean createIndividualFileBackup(Path sourceFile, String operationType) {
        if (Files.notExists(sourceFile)) {
            return true;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS");
        String timestamp = sdf.format(new Date());
        
        Path backupBasePath = Paths.get(pythonBackupDir);
        Path historyDir = backupBasePath.resolve(HISTORY_FOLDER);
        
        Path timeStampDir = historyDir.resolve(timestamp);
        Path operationDir = timeStampDir.resolve(operationType);
        
        Path targetFile = operationDir.resolve(sourceFile.getFileName());

        try {
            Files.createDirectories(operationDir);

            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            
            log.info("📂 [개별 백업 기록] 성공 - 작업: {}, 파일: {}, 위치: {}", 
                     operationType, sourceFile.getFileName(), targetFile.getParent().getFileName());
            return true;
        } catch (IOException e) {
            log.error("❌ [개별 백업 기록] 실패: {} -> {}", sourceFile.getFileName(), e.getMessage(), e);
            return false;
        }
    }


    /**
     * =====================================================================
     * 📌 SHA-256 hash 함수 (운영 파일용)
     * =====================================================================
     */
    private String calcHash(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));

            return sb.toString();

        } catch (Exception e) {
            log.error("파일 해시 계산 오류: {}", file, e);
            return "HASH_ERROR";
        }
    }

    /**
     * =====================================================================
     * 📌 SHA-256 hash (classpath Dev 파일)
     * =====================================================================
     */
    private String calcClasspathHash(String filename) {
        try {
            Resource r = resolver.getResource("classpath:" + CLASSPATH_DIR + filename);

            if (!r.exists()) return "NO_DEV";

            try (InputStream in = r.getInputStream()) {
                byte[] data = in.readAllBytes();

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);

                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));

                return sb.toString();
            }

        } catch (Exception e) {
            log.debug("Classpath 파일 해시 계산 오류 또는 파일 없음: {}", filename);
            return "NO_DEV";
        }
    }

    /**
     * =====================================================================
     * 📌 파일명 안전성 검사 (Path Traversal 차단)
     * =====================================================================
     */
    private boolean isValidName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return false;
        if (!filename.endsWith(".py")) return false;
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) return false;
        return true;
    }

    /**
     * =====================================================================
     * 📌 운영 폴더 Python 파일 목록 조회
     * =====================================================================
     */
    public List<PythonScriptFile> listPythonFiles() {
        List<PythonScriptFile> list = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(pythonWorkingDir), "*.py")) {

            for (Path p : stream) {

                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);

                LocalDateTime lastModified = Instant
                        .ofEpochMilli(attrs.lastModifiedTime().toMillis())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();

                String localHash = calcHash(p);
                String devHash = calcClasspathHash(p.getFileName().toString());

                boolean isNew = !localHash.equals(devHash);

                list.add(new PythonScriptFile(
                        p.getFileName().toString(),
                        attrs.size(),
                        lastModified,
                        isNew,
                        localHash
                ));
            }

        } catch (Exception e) {
            log.error("LIST ERROR: {}", e.getMessage(), e);
        }

        list.sort(Comparator.comparing(PythonScriptFile::getLastModified).reversed());
        return list;
    }

    /**
     * =====================================================================
     * 📌 업로드 (운영 경로로 복사)
     * =====================================================================
     */
    public int saveFiles(List<MultipartFile> files) {

        if (files == null || files.isEmpty()) return 0;

        int count = 0;

        Path workPath = Paths.get(pythonWorkingDir);

        for (MultipartFile file : files) {

            try {
                String filename = file.getOriginalFilename();
                if (!isValidName(filename)) continue;

                Path target = workPath.resolve(filename);

                // 💾 기존 파일 존재 시, 업로드 전 원본 백업
                if (Files.exists(target)) {
                    createIndividualFileBackup(target, OPERATION_UPLOAD_PRE);
                }

                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                
                // 🔥 사용자 작업 이력 로깅
                log.info("✅ [사용자 작업 이력] 파일 업로드 완료: {} (크기: {} bytes)", filename, file.getSize());

                count++;

            } catch (Exception e) {
                log.error("UPLOAD FAIL: {}", e.getMessage(), e);
            }
        }

        return count;
    }

    /**
     * =====================================================================
     * 📌 파일 존재 여부 확인
     * =====================================================================
     */
    public List<String> checkExistingFiles(List<String> filenames) {

        if (filenames == null || filenames.isEmpty()) return Collections.emptyList();

        List<String> exists = new ArrayList<>();
        Path workPath = Paths.get(pythonWorkingDir);

        for (String name : filenames) {
            if (!isValidName(name)) continue;
            Path p = workPath.resolve(name);
            if (Files.exists(p)) exists.add(name);
        }

        return exists;
    }

    /**
     * =====================================================================
     * 📌 안전한 단일 삭제 (Path 검증 포함)
     * =====================================================================
     */
    public boolean deleteFileSafe(String filename) {

        if (!isValidName(filename)) return false;

        try {
            Path p = Paths.get(pythonWorkingDir).resolve(filename);
            
            if (Files.exists(p)) {
                // 💾 삭제 전, 원본 백업
                createIndividualFileBackup(p, OPERATION_DELETE_PRE);
                
                Files.delete(p);
                // 🔥 사용자 작업 이력 로깅
                log.info("✅ [사용자 작업 이력] 파일 삭제 완료: {}", filename);
                return true;
            }

        } catch (Exception e) {
            log.error("DELETE FAIL: {}", e.getMessage(), e);
        }
        return false;
    }

    /**
     * =====================================================================
     * 📌 일괄 삭제
     * =====================================================================
     */
    public int deleteBatchFiles(List<String> list) {

        if (list == null || list.isEmpty()) return 0;

        int ok = 0;

        for (String f : list) {
            if (deleteFileSafe(f)) ok++;
        }

        return ok;
    }

    /**
     * =====================================================================
     * 📌 단일 실행 (Stub)
     * =====================================================================
     */
    public boolean runScript(String filename) {
        if (!isValidName(filename)) return false;

        // 🔥 Stub 운영 — 실제 실행하지 않음
        log.info("Stub 실행 요청됨: {}", filename);

        return true;
    }

    /**
     * =====================================================================
     * 📌 일괄 실행 (Stub)
     * =====================================================================
     */
    public int runBatchScripts(List<String> list) {
        int ok = 0;

        for (String f : list) {
            if (runScript(f)) ok++;
        }

        return ok;
    }

    /**
     * =====================================================================
     * 📌 Dev(classpath) → Prod(운영) 배포 + 전체 백업
     * =====================================================================
     */
    public int deployFiles(List<String> filenames) {

        if (filenames == null || filenames.isEmpty()) return 0;

        int success = 0;

        try {
            Path work = Paths.get(pythonWorkingDir);
            Path backupBase = Paths.get(pythonBackupDir);

            // ⚠️ 배포 시점 백업 폴더 생성
            Path backupDir = backupBase.resolve("backup_" + System.currentTimeMillis());
            Files.createDirectories(backupDir);

            // 운영 전체 백업
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(work, "*.py")) {
                for (Path f : stream) {
                    Files.copy(f, backupDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // 🔥 사용자 작업 이력 로깅
            log.info("✅ [사용자 작업 이력] 배포 전 운영 파일 전체 백업 완료: {}", backupDir.getFileName());

            // Dev(Classpath) → 운영 배포
            for (String name : filenames) {

                if (!isValidName(name)) continue;

                Resource r = resolver.getResource("classpath:" + CLASSPATH_DIR + name);

                if (!r.exists()) {
                    log.warn("DEV 파일 없음: {}", name);
                    continue;
                }

                try (InputStream in = r.getInputStream()) {
                    Files.copy(in, work.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    success++;
                }
            }
             // 🔥 사용자 작업 이력 로깅 
            log.info("✅ [사용자 작업 이력] 배포 완료: {}개 파일", success);


        } catch (Exception e) {
            log.error("DEPLOY ERROR", e);
        }

        return success;
    }
}