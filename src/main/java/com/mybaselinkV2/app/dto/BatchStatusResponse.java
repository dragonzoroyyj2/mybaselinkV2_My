package com.mybaselinkV2.app.dto;

import java.time.LocalDateTime;

/**
 * 배치 작업의 현재 상태를 클라이언트에게 전달하기 위한 DTO (Data Transfer Object).
 * 이는 REST API 응답과 SSE(Server-Sent Events) 데이터로 사용됩니다.
 */
public class BatchStatusResponse {
    private String id;
    private String status; // 예: "RUNNING", "COMPLETED", "FAILED"
    private String message;
    private double progressPercent; // 0.0 ~ 100.0
    private String timestamp;

    public BatchStatusResponse(String id, String status, String message, double progressPercent) {
        this.id = id;
        this.status = status;
        this.message = message;
        this.progressPercent = progressPercent;
        this.timestamp = LocalDateTime.now().toString();
    }

    // --- Getter Methods ---

    public String getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public double getProgressPercent() {
        return progressPercent;
    }

    public String getTimestamp() {
        return timestamp;
    }

    // DTO의 Setter는 일반적으로 필요하지 않지만, JSON 직렬화를 위해 필요할 경우 추가합니다.
    // 여기서는 생성자를 통해 모든 값을 설정합니다.
}