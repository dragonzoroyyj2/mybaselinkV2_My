package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class NewsNaverTypeAService2 {

    private final String CLIENT_ID = "FVzkwJZt2usCrma3m5by";
    private final String CLIENT_SECRET = "CnkokvjlJB";
    
    // ✅ 메모리 누수 방지: 매번 new 하지 않고 필드로 선언
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final List<String> MAJOR_KEYWORDS = Arrays.asList(
            "수주", "공급계약", "흑자전환", "공시", "M&A", "MOU", "투자",
            "상한가", "특징주", "독점", "유상증자", "국책과제", "무상증자", "인수", "단일판매"
    );

    private final DateTimeFormatter naverDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;

    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        List<Map<String, Object>> mockList = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();

        // 1. 타겟 설정 (검색어 여부와 상관없이 '검색' 루프 결정)
        List<String> targets = (search != null && !search.trim().isEmpty()) 
                               ? Collections.singletonList(search) : MAJOR_KEYWORDS;

        // 2. 수집 로직
        for (String word : targets) {
            try {
                // 파이썬과 건수를 맞추기 위해 display를 50으로 유지
                String url = UriComponentsBuilder.fromUriString("https://openapi.naver.com/v1/search/news.json")
                        .queryParam("query", word)
                        .queryParam("display", 50) 
                        .queryParam("sort", "date")
                        .build().toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Naver-Client-Id", CLIENT_ID);
                headers.set("X-Naver-Client-Secret", CLIENT_SECRET);

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode items = objectMapper.readTree(response.getBody()).path("items");

                for (JsonNode item : items) {
                    String link = item.path("link").asText();
                    if (seenLinks.add(link)) {
                        String title = item.path("title").asText()
                                .replace("<b>", "").replace("</b>", "")
                                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'");

                        // ✅ [건수 교정] 파이썬 로직 일치화: 
                        // 검색어가 있으면 해당 검색어 포함 여부 확인, 없으면 15종 키워드 포함 확인
                        boolean isOk = false;
                        if (search != null && !search.trim().isEmpty()) {
                            if (title.contains(search)) isOk = true;
                        } else {
                            for (String k : MAJOR_KEYWORDS) {
                                if (title.contains(k)) {
                                    isOk = true;
                                    break;
                                }
                            }
                        }

                        if (isOk) {
                            LocalDateTime pubDate = LocalDateTime.parse(item.path("pubDate").asText(), naverDateFormatter);
                            Map<String, Object> map = new HashMap<>();
                            map.put("title", title);
                            map.put("owner", "네이버뉴스");
                            map.put("regDate", pubDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            map.put("_rawDate", pubDate); 
                            map.put("serverStatus", "실시간");
                            map.put("featureOption", "핵심재료");
                            map.put("remark", link);
                            mockList.add(map);
                        }
                    }
                }
            } catch (Exception e) {}
        }

        // 3. 최신순 정렬 및 ID 부여
        mockList.sort((a, b) -> ((LocalDateTime) b.get("_rawDate")).compareTo((LocalDateTime) a.get("_rawDate")));
        for (int i = 0; i < mockList.size(); i++) {
            mockList.get(i).put("id", mockList.size() - i);
        }

        // 4. 페이징 구조 적용
        List<Map<String, Object>> filtered = new ArrayList<>(mockList);
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
        List<Map<String, Object>> paged = (start < end) ? filtered.subList(start, end) : new ArrayList<>();

        result.put("content", paged);
        result.put("page", page);
        result.put("totalPages", totalPages);
        result.put("totalElements", totalElements);

        return result;
    }
}