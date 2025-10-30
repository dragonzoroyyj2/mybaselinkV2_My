package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.service.StockBatchService;
import com.mybaselinkV2.app.service.TaskStatusService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StockBatchSseController {

    private final StockBatchService batchService;

    public StockBatchSseController(StockBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping(value = "/api/stock/batch/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = auth != null ? auth.getName() : "anonymous";
        return batchService.createEmitter(user);
    }
}
