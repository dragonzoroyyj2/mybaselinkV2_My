package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybaselinkV2.app.entity.NewsNaverEntity;
import com.mybaselinkV2.app.repository.NewsNaverRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsNaverTypeAService {

    private final NewsNaverRepository repository;

    @Autowired
    public NewsNaverTypeAService(NewsNaverRepository repository) {
        this.repository = repository;
    }

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter naverDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;
    
    private final List<String> MAJOR_KEYWORDS = Arrays.asList(
            "수주", "공급계약", "흑자전환", "공시", "M&A", "MOU", "투자",
            "상한가", "특징주", "독점", "유상증자", "국책과제", "무상증자", "인수", "단일판매"
    );

    /** ✅ 리스트 조회 (수집 + 3일치 청소 + 형님표 Map 반환) */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        
        // 1. [청소] 3일이 지난 데이터는 삭제하여 DB 다이어트
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        repository.deleteByRawDateBefore(threeDaysAgo);

        // 2. [수집] 실시간으로 네이버 데이터 긁어와서 저장 (중복 제외)
        collectAndSave(search);

        // 3. [조회] DB 데이터를 가져와서 Map 리스트로 변환 (ID 내림차순 정렬)
        List<NewsNaverEntity> entities = repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        
        List<Map<String, Object>> filtered = entities.stream().map(entity -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entity.getId());
            map.put("title", entity.getTitle());
            map.put("link", entity.getLink());
            map.put("owner", entity.getOwner());
            map.put("regDate", entity.getRegDate());
            map.put("serverStatus", entity.getServerStatus());
            map.put("featureOption", entity.getFeatureOption());
            map.put("remark", ""); 
            return map;
        }).collect(Collectors.toCollection(ArrayList::new));

        // 4. [검색] 형님의 기존 필터링 로직 (SafeStr 기반)
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

        // 5. [결과 반환] 페이지네이션 및 구조화
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
        
        List<Map<String, Object>> paged = (start >= totalElements) 
                                          ? new ArrayList<>() 
                                          : filtered.subList(start, end);

        result.put("content", paged);
        result.put("page", page);
        result.put("totalPages", totalPages);
        result.put("totalElements", totalElements);

        return result;
    }

    private String safeStr(Object obj) {
        return obj == null ? "" : obj.toString();
    }

    /** ✅ 데이터 수집 및 중복 체크 저장 로직 */
    private void collectAndSave(String search) {
        List<String> targets = (search != null && !search.trim().isEmpty()) 
                               ? Collections.singletonList(search) : MAJOR_KEYWORDS;

        for (String word : targets) {
            try {
                String url = UriComponentsBuilder.fromUriString("https://openapi.naver.com/v1/search/news.json")
                        .queryParam("query", word)
                        .queryParam("display", 50)
                        .queryParam("sort", "date")
                        .build().toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Naver-Client-Id", "FVzkwJZt2usCrma3m5by");
                headers.set("X-Naver-Client-Secret", "CnkokvjlJB");

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode items = objectMapper.readTree(response.getBody()).path("items");

                for (JsonNode item : items) {
                    String link = item.path("link").asText();
                    String title = item.path("title").asText()
                            .replace("<b>", "").replace("</b>", "")
                            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'");

                    // 🚩 중복 체크: 제목과 링크가 모두 DB에 없을 때만 저장
                    if (isValid(title, search) && !repository.existsByLink(link) && !repository.existsByTitle(title)) {
                        LocalDateTime pubDate = LocalDateTime.parse(item.path("pubDate").asText(), naverDateFormatter);
                        repository.save(new NewsNaverEntity(
                            title, link, "네이버뉴스", 
                            pubDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), 
                            pubDate, "실시간", "핵심재료"
                        ));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isValid(String title, String search) {
        if (search != null && !search.isEmpty()) return title.contains(search);
        for (String k : MAJOR_KEYWORDS) if (title.contains(k)) return true;
        return false;
    }
}