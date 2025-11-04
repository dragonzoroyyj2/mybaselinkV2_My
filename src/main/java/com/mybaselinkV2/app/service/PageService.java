package com.mybaselinkV2.app.service;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

/**
 * 📘 PageService
 * ------------------------------------------------------
 * ✅ 각 페이지별 제목(title), 경로(breadcrumb), 아이콘(meta) 관리
 * ✅ 현재는 Map 기반 (정적)
 * ✅ 나중에 DB 연동 시 Repository로 교체만 하면 됨
 * ------------------------------------------------------
 */
@Service
public class PageService {

    /** ✅ 페이지별 메타정보 (title, breadcrumb) */
    private static final Map<String, String[]> PAGE_META = new HashMap<>() {{
    	
   		// (예: pages/stock/stockList)
    	
    		put("pages/p01/p01a05/p01a05List", new String[]{"🖥️ 서버 리스트", 					"리포트 관리 / 서버 리스트"});    
    		
    		put("pages/stock/stockBatchBoard", new String[]{"📋 종목 일괄 업데이트", 			"Stock 관리 / 일괄 업데이트"});
    	
    		put("pages/stock/stockList", new String[]{"📊 주식 종목 리스트", 					"Stock 관리 / 종목 리스트"});
    		put("pages/stock/stockLastCloseDownward", new String[]{"📉 연속 하락 종목 분석", 	"Stock 관리 / 연속 하락 종목 분석"});
        
        put("pages/sy/syusr/syusr01List", new String[]{"⚙️ 설정", 							"사용자 관리 / 사용자 리스트"});
        // 👉 필요 시 여기에 계속 추가 가능
    }};

    /**
     * ✅ 페이지 메타정보 조회
     * 
     * @param path 요청된 페이지 경로 (예: pages/stock/stockList)
     * @return [제목, 경로] 배열
     */
    public String[] getMeta(String path) {
        return PAGE_META.getOrDefault(path, new String[]{"📄 일반 페이지", "시스템 / 기타"});
    }
}
