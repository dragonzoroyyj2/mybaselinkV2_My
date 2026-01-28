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
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsRssTypeAService {

    private final NewsIntegratedRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper(); 
    
    // 🚩 화면 표시용 날짜 포맷 (통일)
    private final DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${python.stock.json.path}")
    private String script_json_path;

    @Autowired
    public NewsRssTypeAService(NewsIntegratedRepository repository) {
        this.repository = repository;
    }

    private final List<Map<String, String>> RSS_SOURCES = Arrays.asList(
        Map.of("name", "연합뉴스", "url", "https://www.yonhapnewstv.co.kr/browse/feed/"),
        Map.of("name", "매일경제", "url", "https://www.mk.co.kr/rss/30200030/"),
        Map.of("name", "한국경제", "url", "https://www.hankyung.com/feed/finance"),
        Map.of("name", "머니투데이", "url", "https://rss.mt.co.kr/mt_news.xml"),
        Map.of("name", "파이낸셜뉴스", "url", "https://www.fnnews.com/rss/r20/fn_realnews_stock.xml"),
        Map.of("name", "서울경제", "url", "https://www.sedaily.com/rss/finance"),
        Map.of("name", "아시아경제", "url", "https://www.asiae.co.kr/rss/stock.htm"),
        Map.of("name", "헤럴드경제", "url", "https://biz.heraldcorp.com/rss/google/finance"),
        Map.of("name", "뉴시스속보", "url", "https://www.newsis.com/RSS/sokbo.xml"),
        Map.of("name", "뉴시스금융", "url", "https://www.newsis.com/RSS/bank.xml")
    );

    private final List<String> POSITIVE_KEYWORDS = Arrays.asList(
        "상승", "돌파", "수주", "공급계약", "최고치", "흑자전환", "실적개선", "사상최대", "영업익 증", "매출 증", "서프라이즈",
        "M&A", "인수", "독점", "특허", "임상", "승인", "양해각서", "MOU", "협력", "파트너십", "제휴",
        "급등", "상한가", "신고가", "증설", "강세", "반등", "질주", "훈풍", "유입", "순매수", "상향", "추천",
        "신기술", "상용화", "국산화", "최초", "IPO", "상장", "액면분할", "무상증자", "배당", "특징주"
    );

    /** ✅ 종목 마스터 로드 (script_json_path 사용) */
    private List<String> getStockMasterFromJson() {
        try {
            // 🚩 하드코딩 로직 제거 및 주입된 경로 사용
            File jsonFile = new File(script_json_path);
            
            if (!jsonFile.exists()) {
                System.out.println("⚠️ [RSS] 종목 리스트 파일을 찾을 수 없습니다: " + script_json_path);
                return new ArrayList<>();
            }

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
            stockList.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return stockList;
        } catch (IOException e) { 
            e.printStackTrace();
            return new ArrayList<>(); 
        }
    }

    /** ✅ 종목 코드로 찾기 (script_json_path 사용) */
    private String findStockCodeByName(String stockName) {
        if (stockName == null || stockName.isEmpty()) return "";
        try {
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

    /** ✅ 종목명 추출 (추출 로직 고도화 적용) */
    private String extractStockName(String title, List<String> stockMaster) {
        if (title == null || title.isEmpty() || stockMaster == null || stockMaster.isEmpty()) return "";
        
        // 특수문자 제거 후 매칭률 향상
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

    private String calculateServerStatus(LocalDateTime rawDate) {
        if (rawDate == null) return "-";
        LocalDateTime now = LocalDateTime.now();
        long daysBetween = ChronoUnit.DAYS.between(rawDate.toLocalDate(), now.toLocalDate());
        return (daysBetween == 0) ? "오늘" : daysBetween + "일 전";
    }

    /** ✅ 리스트 조회 */
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));
        collectRssNews();

        List<NewsIntegratedEntity> entities = repository.findByNewsType("RSS", Sort.by(Sort.Direction.DESC, "rawDate"));
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

    private String safeStr(Object obj) { return obj == null ? "" : obj.toString(); }

    /** ✅ RSS 수집 및 저장 */
    private void collectRssNews() {
        List<String> stockMaster = getStockMasterFromJson();
        for (Map<String, String> source : RSS_SOURCES) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", "Mozilla/5.0");
                ResponseEntity<byte[]> response = restTemplate.exchange(source.get("url"), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
                if (response.getBody() == null) continue;

                DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = builder.parse(new ByteArrayInputStream(response.getBody()));
                NodeList items = doc.getElementsByTagName("item");

                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String title = getTagValue("title", item);
                    String link = getTagValue("link", item);
                    String matchedKeyword = findMatchedKeyword(title);

                    if (!repository.existsByLink(link) && !repository.existsByTitle(title)) {
                        String stockName = extractStockName(title, stockMaster);
                        
                        if (!stockName.isEmpty() || matchedKeyword != null) {
                            String stockCode = (!stockName.isEmpty()) ? findStockCodeByName(stockName) : "";
                            String feature = (matchedKeyword != null) ? matchedKeyword : "정보";
                            String finalStockName = (!stockName.isEmpty()) ? stockName : source.get("name");
                            
                            LocalDateTime now = LocalDateTime.now();

                            repository.save(new NewsIntegratedEntity(
                                stockCode, 
                                finalStockName, 
                                title, 
                                link, 
                                now, 
                                feature, 
                                calculateServerStatus(now), 
                                "RSS"
                            ));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private String findMatchedKeyword(String title) {
        if (title == null) return null;
        return POSITIVE_KEYWORDS.stream().filter(title::contains).findFirst().orElse(null);
    }

    private String getTagValue(String tag, Element element) {
        NodeList nlList = element.getElementsByTagName(tag);
        if (nlList.getLength() > 0 && nlList.item(0).hasChildNodes()) {
            return nlList.item(0).getFirstChild().getNodeValue().trim();
        }
        return "";
    }

    /** ✅ Map 변환: rawDate 포맷팅 및 regDate 키 추가 */
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