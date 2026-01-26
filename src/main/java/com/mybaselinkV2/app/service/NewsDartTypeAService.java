package com.mybaselinkV2.app.service;

import com.mybaselinkV2.app.entity.NewsDartEntity;
import com.mybaselinkV2.app.repository.NewsDartRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NewsDartTypeAService {

    @Autowired
    private NewsDartRepository repository;

    private final String API_KEY = "599b24c052bb23453a48da3916ae7faf1befd03e";
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, String> profitStatusCache = new ConcurrentHashMap<>();

    private final List<String> GOOD_KEYWORDS = Arrays.asList(
            "공급계약", "수주", "판매계약", "체결", "흑자전환",
            "영업이익증가", "무상증자", "자사주소각", "자사주취득", "인수", "합병", "단일판매"
    );

    /** ✅ 네이버 서비스 스타일로 통합된 리스트 조회 */
    @Transactional
    public Map<String, Object> getList(int page, int size, String search, String mode, boolean pagination) {
        
        // 1. [청소] 3일 지난 데이터 삭제
        repository.deleteByRawDateBefore(LocalDateTime.now().minusDays(3));

        // 2. [수집] 실시간 DART 데이터 긁어와서 저장 (중복 제외)
        collectAndSave();

        // 3. [조회] DB 데이터 가져와서 Map 리스트로 변환
        List<NewsDartEntity> entities = repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        
        List<Map<String, Object>> filtered = entities.stream()
            .filter(e -> {
                if (search == null || search.trim().isEmpty() || "1".equals(search)) return true;
                else if ("3".equals(search)) return GOOD_KEYWORDS.stream().anyMatch(k -> e.getTitle().contains(k));
                else return e.getTitle().contains(search) || e.getOwner().contains(search);
            })
            .map(e -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", e.getId());
                item.put("title", e.getTitle());
                item.put("owner", e.getOwner());
                item.put("regDate", e.getRegDate());
                item.put("serverStatus", e.getServerStatus());
                item.put("featureOption", e.getFeatureOption());
                item.put("remark", e.getLink());
                return item;
            })
            .collect(Collectors.toList());

        // 4. [결과 반환] 페이징 처리
        Map<String, Object> result = new HashMap<>();
        int totalElements = filtered.size();
        
        if (!pagination || "client".equalsIgnoreCase(mode)) {
            result.put("content", filtered);
            result.put("page", 0);
            result.put("totalPages", 1);
            result.put("totalElements", totalElements);
            return result;
        }

        int totalPages = (size > 0) ? (int) Math.ceil((double) totalElements / size) : 0;
        int start = page * size;
        List<Map<String, Object>> paged = (totalElements == 0 || start >= totalElements) 
                                          ? new ArrayList<>() 
                                          : filtered.subList(start, Math.min(start + size, totalElements));

        result.put("content", paged);
        result.put("page", page);
        result.put("totalPages", totalPages);
        result.put("totalElements", totalElements);
        return result;
    }

    /** ✅ 데이터 수집 로직 (Private으로 변경하여 getList 내부에서 호출) */
    private void collectAndSave() {
        LocalDate targetLocalDate = LocalDate.now();
        // 아침 7:30 전이면 어제 공시부터 가져옴
        if (LocalTime.now().isBefore(LocalTime.of(7, 30))) targetLocalDate = targetLocalDate.minusDays(1);
        String targetDate = targetLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        try {
            String url = "https://opendart.fss.or.kr/api/list.json";
            String targetUrl = UriComponentsBuilder.fromUriString(url) 
                    .queryParam("crtfc_key", API_KEY)
                    .queryParam("bgnde", targetDate)
                    .queryParam("endde", targetDate)
                    .queryParam("page_count", "100")
                    .toUriString();

            String response = restTemplate.getForObject(targetUrl, String.class);
            if (response == null) return;

            JSONObject json = new JSONObject(response);
            if ("000".equals(json.optString("status"))) {
                JSONArray list = json.getJSONArray("list");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject obj = list.getJSONObject(i);
                    String corpCls = obj.optString("corp_cls");
                    if (!Arrays.asList("Y", "K", "N").contains(corpCls)) continue;

                    String link = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + obj.optString("rcept_no");
                    
                    // 🚩 중복 체크: 이미 저장된 링크면 패스
                    if (repository.existsByLink(link)) continue;

                    String corpCode = obj.optString("corp_code");
                    // 재무 상태 확인 (속도 저하 방지를 위해 캐시 사용)
                    String feature = profitStatusCache.computeIfAbsent(corpCode, this::getProfitStatusFromDart);

                    NewsDartEntity entity = new NewsDartEntity(
                            obj.optString("report_nm"), link, obj.optString("corp_name"),
                            obj.optString("rcept_dt"), LocalDateTime.now(),
                            getMarketName(corpCls), feature
                    );
                    repository.save(entity);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getProfitStatusFromDart(String corpCode) {
        String url = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json";
        String currentYear = String.valueOf(LocalDate.now().getYear());
        String lastYear = String.valueOf(LocalDate.now().getYear() - 1);
        String[] years = {currentYear, lastYear};
        String[][] reports = {{"11014", "3분기"}, {"11012", "반기"}, {"11013", "1분기"}, {"11011", "결산"}};

        for (String year : years) {
            for (String[] r : reports) {
                try {
                    String targetUrl = UriComponentsBuilder.fromUriString(url) 
                            .queryParam("crtfc_key", API_KEY)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", year)
                            .queryParam("reprt_code", r[0])
                            .toUriString();

                    String response = restTemplate.getForObject(targetUrl, String.class);
                    if (response == null) continue;
                    JSONObject json = new JSONObject(response);
                    if ("000".equals(json.optString("status")) && json.has("list")) {
                        JSONArray list = json.getJSONArray("list");
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            if (item.optString("account_nm").contains("영업이익")) {
                                String valStr = item.optString("thstrm_amount").replace(",", "");
                                if (!valStr.isEmpty() && !valStr.equals("-")) {
                                    return (Long.parseLong(valStr) > 0 ? "[흑자]" : "[적자]") + " ("+year+" "+r[1]+")";
                                }
                            }
                        }
                    }
                } catch (Exception e) {}
            }
        }
        return "[재무미확인]";
    }

    private String getMarketName(String cls) {
        if ("Y".equals(cls)) return "코스피";
        if ("K".equals(cls)) return "코스닥";
        if ("N".equals(cls)) return "코넥스";
        return "기타";
    }
}