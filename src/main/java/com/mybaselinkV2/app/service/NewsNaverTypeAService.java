package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybaselinkV2.app.entity.NewsIntegratedEntity;
import com.mybaselinkV2.app.repository.NewsIntegratedRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsNaverTypeAService {

    private final NewsIntegratedRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter naverDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;
    
    // 🚩 화면 표시용 날짜 포맷
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${python.stock.json.path}")
    private String script_json_path;
    
    @Autowired
    public NewsNaverTypeAService(NewsIntegratedRepository repository) {
        this.repository = repository;
    }
          
    private final List<String> MAJOR_KEYWORDS = Arrays.asList(
            "수주", "공급계약", "흑자전환", "공시", "M&A", "MOU", "투자",
            "상한가", "특징주", "독점", "유상증자", "국책과제", "무상증자", "인수", "단일판매",
            "상승", "돌파", "최고치", "실적개선", "사상최대", "급등", "신고가", "강세"
    );

    /** ✅ JSON에서 종목명 로드 (주입된 script_json_path 사용) */
    private List<String> getStockMasterFromJson() {
        try {
            // 🚩 하드코딩 경로 제거하고 형님이 만든 script_json_path 사용
            File jsonFile = new File(script_json_path);
            
            if (!jsonFile.exists()) {
                System.out.println("⚠️ [Naver] 종목 리스트 파일을 찾을 수 없습니다: " + script_json_path);
                return new ArrayList<>();
            }

            // StandardCharsets.UTF_8 명시로 인코딩 깨짐 방지
            JsonNode root = objectMapper.readTree(jsonFile);
            List<String> stockList = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("Name")) {
                        String name = node.get("Name").asText().trim();
                        if (!name.isEmpty()) stockList.add(name);
                    }
                }
            }
            // 글자수가 긴 것부터 매칭되도록 정렬
            stockList.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return stockList;
        } catch (IOException e) { 
            e.printStackTrace();
            return new ArrayList<>(); 
        }
    }

    /** ✅ 종목코드로 찾기 (주입된 script_json_path 사용) */
    private String findStockCodeByName(String stockName) {
        if (stockName == null || stockName.isEmpty() || stockName.equals("네이버뉴스")) return "";
        try {
            // 🚩 여기도 script_json_path 사용
            File jsonFile = new File(script_json_path);
            if (!jsonFile.exists()) return "";

            JsonNode root = objectMapper.readTree(jsonFile);
            if (root.isArray()) {
                String targetName = stockName.replace(" ", "").toUpperCase();
                for (JsonNode node : root) {
                    if (node.has("Name") && node.has("Code")) {
                        String jsonName = node.get("Name").asText().replace(" ", "").toUpperCase();
                        if (jsonName.equals(targetName)) {
                            return node.get("Code").asText().trim();
                        }
                    }
                }
            }
        } catch (Exception e) { return ""; }
        return "";
    }

    /** ✅ 종목명 추출 (추출 로직 유지) */
    private String extractStockName(String title, List<String> stockMaster) {
        if (title == null || title.isEmpty() || stockMaster == null || stockMaster.isEmpty()) return "";
        
        String cleanTitle = title.replaceAll("[^가-힣a-zA-Z0-9]", "").toUpperCase();
        
        for (String stock : stockMaster) {
            String originStock = stock.toUpperCase().replace(" ", "");
            if (cleanTitle.contains(originStock)) return stock;
            if (originStock.length() >= 4) {
                String head = originStock.substring(0, 2);
                String tail = originStock.substring(2);
                if (cleanTitle.contains(head + tail.substring(0, Math.min(2, tail.length()))) || 
                    (tail.length() >= 2 && cleanTitle.contains(tail))) {
                    return stock;
                }
            }
        }
        return "";
    }

    private String findMatchedKeyword(String title) {
        if (title == null) return "일반";
        return MAJOR_KEYWORDS.stream().filter(title::contains).findFirst().orElse("재료");
    }

    private String calculateServerStatus(LocalDateTime rawDate) {
        if (rawDate == null) return "-";
        LocalDateTime now = LocalDateTime.now();
        long daysBetween = ChronoUnit.DAYS.between(rawDate.toLocalDate(), now.toLocalDate());
        return (daysBetween == 0) ? "오늘" : daysBetween + "일 전";
    }

    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));
        collectAndSave(search);
        
        List<NewsIntegratedEntity> entities = repository.findByNewsType("NAVER", Sort.by(Sort.Direction.DESC, "rawDate"));
        
        List<Map<String, Object>> filtered = entities.stream()
                .map(this::convertToMap)
                .filter(item -> {
                    if (search == null || search.isEmpty()) return true;
                    String s = search.toLowerCase();
                    return safeStr(item.get("title")).toLowerCase().contains(s) || 
                           safeStr(item.get("stockName")).toLowerCase().contains(s) ||
                           safeStr(item.get("stockCode")).toLowerCase().contains(s);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return applyPagination(filtered, page, size, mode, pagination);
    }

    private void collectAndSave(String search) {
        List<String> targets = (search != null && !search.trim().isEmpty() && !search.equals("1")) 
                               ? Collections.singletonList(search) : MAJOR_KEYWORDS;
        List<String> stockMaster = getStockMasterFromJson();

        for (String word : targets) {
            try {
                String url = UriComponentsBuilder.fromUriString("https://openapi.naver.com/v1/search/news.json")
                        .queryParam("query", word).queryParam("display", 50).queryParam("sort", "date")
                        .build().toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Naver-Client-Id", "FVzkwJZt2usCrma3m5by");
                headers.set("X-Naver-Client-Secret", "CnkokvjlJB");

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode items = objectMapper.readTree(response.getBody()).path("items");

                for (JsonNode item : items) {
                    String link = item.path("link").asText();
                    String rawTitle = item.path("title").asText();
                    String cleanTitle = rawTitle.replaceAll("<[^>]*>", "").replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");

                    if (!repository.existsByLink(link) && !repository.existsByTitle(cleanTitle)) {
                        LocalDateTime pubDate = LocalDateTime.parse(item.path("pubDate").asText(), naverDateFormatter);
                        String stockName = extractStockName(cleanTitle, stockMaster);
                        
                        String finalStockName = (stockName != null && !stockName.isEmpty()) ? stockName : "네이버뉴스";
                        String stockCode = findStockCodeByName(finalStockName);
                        String feature = findMatchedKeyword(cleanTitle);

                        repository.save(new NewsIntegratedEntity(
                            stockCode, 
                            finalStockName, 
                            cleanTitle, 
                            link, 
                            pubDate, 
                            feature, 
                            calculateServerStatus(pubDate), 
                            "NAVER"
                        ));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private String safeStr(Object obj) { return obj == null ? "" : obj.toString(); }

    private Map<String, Object> convertToMap(NewsIntegratedEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("link", entity.getLink());
        map.put("stockName", entity.getStockName());
        map.put("stockCode", entity.getStockCode()); 
        
        String formattedDate = entity.getRawDate().format(displayFormatter);
        map.put("regDate", formattedDate);
        map.put("rawDate", formattedDate);
        
        map.put("serverStatus", calculateServerStatus(entity.getRawDate()));
        map.put("featureOption", entity.getFeatureOption());
        return map;
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> list, int page, int size, String mode, boolean pagination) {
        Map<String, Object> result = new HashMap<>();
        int total = list.size();
        if (!pagination || "client".equalsIgnoreCase(mode)) {
            result.put("content", list);
            result.put("totalElements", total);
            return result;
        }
        int start = page * size;
        int end = Math.min(start + size, total);
        result.put("content", (start >= total) ? new ArrayList<>() : list.subList(start, end));
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        return result;
    }
}