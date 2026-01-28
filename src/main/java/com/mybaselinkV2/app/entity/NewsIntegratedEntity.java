package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/* ===============================================================
   ✅ NewsIntegratedEntity (NAVER, RSS 통합 캐시용)
   ---------------------------------------------------------------
   [형님 요구사항 반영]
   - owner 필드 삭제 -> stockName으로 통합
   - regDate 삭제 -> rawDate(LocalDateTime)로 시:분 관리
   - DART 엔티티와 구조를 완벽히 일치시켜 UNION 조회 가능하게 구성
================================================================ */

@Entity
@Table(name = "news_integrated_cache", schema = "mybaselink")
public class NewsIntegratedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 20)
    private String stockCode;      // 종목코드

    @Column(name = "stock_name", length = 100)
    private String stockName;      // 종목명 또는 출처 (통합)

    @Column(length = 500, nullable = false)
    private String title;          // 뉴스 제목

    @Column(length = 1000, nullable = false, unique = true)
    private String link;           // 뉴스 원문 링크

    @Column(name = "raw_date", nullable = false)
    private LocalDateTime rawDate; // 네이버와 동일한 시간 기준 (정렬/출력용)

    @Column(name = "feature_option", length = 100)
    private String featureOption;  // 키워드 (급등, 수주 등)

    @Column(name = "server_status", length = 50)
    private String serverStatus;   // 상태 정보 (ON/OFF 등)

    @Column(name = "news_type", length = 20)
    private String newsType;       // ✅ NAVER / RSS / DART 구분자

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public NewsIntegratedEntity() {}

    // 통합 생성자
    public NewsIntegratedEntity(String stockCode, String stockName, String title, String link, 
                                LocalDateTime rawDate, String featureOption, String serverStatus, String newsType) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.title = title;
        this.link = link;
        this.rawDate = rawDate;
        this.featureOption = featureOption;
        this.serverStatus = serverStatus;
        this.newsType = newsType;
    }

    // Getter & Setter (이하 생략 - 필요한 것만 생성)
    public Long getId() { return id; }
    public String getStockCode() { return stockCode; }
    public String getStockName() { return stockName; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public LocalDateTime getRawDate() { return rawDate; }
    public String getFeatureOption() { return featureOption; }
    public String getServerStatus() { return serverStatus; }
    public String getNewsType() { return newsType; }

    public void setStockName(String stockName) { this.stockName = stockName; }
    public void setRawDate(LocalDateTime rawDate) { this.rawDate = rawDate; }
}