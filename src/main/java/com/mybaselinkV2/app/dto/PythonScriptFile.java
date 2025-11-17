package com.mybaselinkV2.app.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =====================================================================
 * 📁 PythonScriptFile (리빌드 완전체)
 * ---------------------------------------------------------------------
 * ✔ Python 운영 파일 메타데이터 DTO
 * ✔ lastModified
 * ✔ isNew (운영 ↔ Dev 해시 비교)
 * ✔ 운영 hash
 * ✔ Dev hash (새로 추가)
 * ✔ uploadDate (화면 표시용 yyyy.MM.dd HH:mm)
 * =====================================================================
 */
public class PythonScriptFile {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final String filename;
    private final long size;
    private final String uploadDate;
    private final LocalDateTime lastModified;
    private final boolean isNew;
    private final String hash;     // 운영 파일 SHA-256
    private final String devHash;  // DEV(Classpath) SHA-256 (없으면 NO_DEV)

    /**
     * =====================================================================
     * 📌 생성자 (리빌드 완전체)
     * =====================================================================
     */
    public PythonScriptFile(
            String filename,
            long size,
            LocalDateTime lastModified,
            boolean isNew,
            String hash,
            String devHash
    ) {
        this.filename = filename;
        this.size = size;
        this.lastModified = lastModified;
        this.uploadDate = lastModified.format(DATE_FORMATTER);
        this.isNew = isNew;
        this.hash = hash;
        this.devHash = devHash;
    }

    /** 기존 생성자 (호환성 유지) */
    public PythonScriptFile(
            String filename,
            long size,
            LocalDateTime lastModified,
            boolean isNew,
            String hash
    ) {
        this(filename, size, lastModified, isNew, hash, "NO_DEV");
    }

    // ============================================================
    // Getter
    // ============================================================

    public String getFilename() {
        return filename;
    }

    public long getSize() {
        return size;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public String getUploadDate() {
        return uploadDate;
    }

    public boolean isNew() {
        return isNew;
    }

    public boolean getNew() { // JSON 직렬화 시 "new" 필드
        return isNew;
    }

    public String getHash() {
        return hash;
    }

    public String getDevHash() {
        return devHash;
    }
}
