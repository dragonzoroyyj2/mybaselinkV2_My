package com.mybaselinkV2.app.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mybaselinkV2.app.service.StockBatchProdService;

@RestController
public class StockBatchProdSseController {

    private final StockBatchProdService batchProdService;

    public StockBatchProdSseController(StockBatchProdService batchProdService) {
        this.batchProdService = batchProdService;
    }

    @GetMapping(value = "/api/stock/batch/prod/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String user = auth != null ? auth.getName() : "anonymous";
        return batchProdService.createEmitter(user);
    }
}
