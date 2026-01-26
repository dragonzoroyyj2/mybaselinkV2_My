package com.mybaselinkV2.app.service;

import com.mybaselinkV2.app.entity.NewsRssEntity;
import com.mybaselinkV2.app.repository.NewsRssRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsRssTypeAService {

    private final NewsRssRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    public NewsRssTypeAService(NewsRssRepository repository) {
        this.repository = repository;
    }

    // 🚩 파이썬의 RSS 소스 리스트 그대로 이식
    private final List<Map<String, String>> RSS_SOURCES = Arrays.asList(
        Map.of("name", "연합뉴스", "url", "https://www.yonhapnewstv.co.kr/browse/feed/"),
        Map.of("name", "매일경제", "url", "https://www.mk.co.kr/rss/30200030/"),
        Map.of("name", "한국경제", "url", "https://www.hankyung.com/feed/finance"),
        Map.of("name", "머니투데이", "url", "https://rss.mt.co.kr/mt_news.xml"),
        Map.of("name", "파이낸셜뉴스", "url", "https://www.fnnews.com/rss/r20/fn_realnews_stock.xml"),
        Map.of("name", "서울경제", "url", "https://www.sedaily.com/rss/finance")
    );

    // 🚩 파이썬의 긍정 키워드 그대로 이식
    private final List<String> POSITIVE_KEYWORDS = Arrays.asList(
        "상승", "돌파", "수주", "공급계약", "최고치", "흑자전환", "실적개선", "사상최대", "영업익 증", "매출 증", "서프라이즈",
        "M&A", "인수", "독점", "특허", "임상", "승인", "양해각서", "MOU", "협력", "파트너십", "제휴",
        "급등", "상한가", "신고가", "증설", "강세", "반등", "질주", "훈풍", "유입", "순매수", "상향", "추천",
        "신기술", "상용화", "국산화", "최초", "IPO", "상장", "액면분할", "무상증자", "배당"
    );

    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        // 1. 3일치 데이터 청소
        repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));

        // 2. RSS 수집 실행
        collectRssNews();

        // 3. DB 데이터 조회 및 변환
        List<NewsRssEntity> entities = repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        List<Map<String, Object>> filtered = entities.stream().map(this::convertToMap).collect(Collectors.toCollection(ArrayList::new));

        // 4. 검색 필터링
        if (search != null && !search.isEmpty()) {
            String s = search.toLowerCase();
            filtered.removeIf(item -> !item.get("title").toString().toLowerCase().contains(s));
        }

        // 5. 페이징 처리
        return applyPagination(filtered, page, size, mode, pagination);
    }

    private void collectRssNews() {
        for (Map<String, String> source : RSS_SOURCES) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_XML));

                ResponseEntity<byte[]> response = restTemplate.exchange(
                    source.get("url"), 
                    HttpMethod.GET, 
                    new HttpEntity<>(headers), 
                    byte[].class
                );

                if (response.getBody() == null) continue;

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                // 보안을 위한 설정 추가 (XXE 공격 방지)
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(new ByteArrayInputStream(response.getBody()));
                doc.getDocumentElement().normalize();
                
                NodeList items = doc.getElementsByTagName("item");

                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String title = getTagValue("title", item);
                    String link = getTagValue("link", item);
                    
                    if (isPositive(title) && !repository.existsByLink(link) && !repository.existsByTitle(title)) {
                        repository.save(new NewsRssEntity(
                            title, link, source.get("name"), 
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            LocalDateTime.now(), "RSS", "긍정"
                        ));
                    }
                }
            } catch (Exception e) {
                // 에러 발생 시 로그만 찍고 다음 소스로 넘어감
                System.err.println("[RSS 수집 실패] " + source.get("name") + " : " + e.getMessage());
            }
        }
    }

    private boolean isPositive(String title) {
        if (title == null) return false;
        return POSITIVE_KEYWORDS.stream().anyMatch(title::contains);
    }

    private String getTagValue(String tag, Element element) {
        NodeList nlList = element.getElementsByTagName(tag);
        if (nlList.getLength() > 0 && nlList.item(0).hasChildNodes()) {
            return nlList.item(0).getFirstChild().getNodeValue().trim();
        }
        return "";
    }

    private Map<String, Object> convertToMap(NewsRssEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("link", entity.getLink());
        map.put("owner", entity.getOwner());
        map.put("regDate", entity.getRegDate());
        return map;
    }

    private Map<String, Object> applyPagination(List<Map<String, Object>> list, int page, int size, String mode, boolean pagination) {
        Map<String, Object> result = new HashMap<>();
        int totalElements = list.size();
        
        if (!pagination || "client".equalsIgnoreCase(mode)) {
            result.put("content", list);
            result.put("totalElements", totalElements);
            return result;
        }
        
        int start = page * size;
        int end = Math.min(start + size, totalElements);
        result.put("content", (start >= totalElements) ? new ArrayList<>() : list.subList(start, end));
        result.put("totalElements", totalElements);
        result.put("totalPages", (int) Math.ceil((double) totalElements / size));
        return result;
    }
}