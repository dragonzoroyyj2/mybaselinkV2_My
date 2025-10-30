package com.mybaselinkV2.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private final Map<String, List<SseEmitter>> channels = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String channel, Consumer<SseEmitter> onConnect) {
        SseEmitter emitter = new SseEmitter(0L); // 타임아웃 무한
        channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(channel, emitter));
        emitter.onTimeout(() -> remove(channel, emitter));
        emitter.onError(e -> remove(channel, emitter));

        // 새 연결 콜백 실행
        if (onConnect != null) {
            onConnect.accept(emitter);
        }

        log.info("[SSE] 구독 채널 연결: {}", channel);
        return emitter;
    }

    public void broadcast(String channel, String event, Object data) {
        List<SseEmitter> list = channels.get(channel);
        if (list == null) return;

        for (SseEmitter emitter : new ArrayList<>(list)) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException e) {
                remove(channel, emitter);
            }
        }
    }

    public void sendToEmitter(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            // 단일 송신 실패 무시
        }
    }

    private void remove(String channel, SseEmitter emitter) {
        List<SseEmitter> list = channels.get(channel);
        if (list != null) list.remove(emitter);
        log.info("[SSE] 연결 해제: {}", channel);
    }

    public void clear(String channel) {
        List<SseEmitter> list = channels.remove(channel);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }
}
