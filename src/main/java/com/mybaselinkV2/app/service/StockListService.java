package com.mybaselinkV2.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * StockListService
 * --------------------------------------------------------
 * python/stock/stock_list/stock_listing.json 읽기
 * --------------------------------------------------------
 */
@Service
public class StockListService {

    private final ObjectMapper mapper = new ObjectMapper();
    
    // ✅ 프로퍼티 값 주입 시, 기본값을 설정하여 파일이 없는 경우 대비
    @Value("${python.stock.stock_listing.path:}")
    private String localPath;
    

    private File resolveJsonFile() throws IOException {
        // String localPath = "C:\LocBootProject\workspace\MyBaseLink\python\stock\stock_list\stock_listing.json";
        if (StringUtils.hasText(localPath)) {
            File file = new File(localPath);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        return new ClassPathResource("data/stock_listing.json").getFile();
    }

    /**
     * getStockList() 메서드에 id 추가 로직을 포함
     */
    public List<Map<String, Object>> getStockList() throws IOException {
        File jsonFile = resolveJsonFile();
        List<Map<String, Object>> stockList = mapper.readValue(jsonFile, new TypeReference<List<Map<String, Object>>>() {});
        
        // ✅ 각 항목에 고유한 id 추가
        for (int i = 0; i < stockList.size(); i++) {
            stockList.get(i).put("id", i + 1); // 1부터 시작하는 id
        }
        
        return stockList;
    }
}