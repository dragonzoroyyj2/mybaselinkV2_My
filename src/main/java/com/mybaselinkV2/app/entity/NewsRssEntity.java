package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_rss_cache") // 🚩 RSS 전용 테이블 이름
public class NewsRssEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String title;

    @Column(length = 1000, nullable = false)
    private String link;

    @Column(length = 100)
    private String owner;       // 신문사 이름 (연합뉴스, 매일경제 등)

    @Column(length = 50)
    private String regDate;     // 화면 표시용 날짜 (yyyy-MM-dd HH:mm)

    @Column(nullable = false)
    private LocalDateTime rawDate; // 청소(3일치) 및 정렬용 날짜

    @Column(length = 50)
    private String serverStatus;

    @Column(length = 50)
    private String featureOption;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ✅ 기본 생성자 (JPA 필수)
    public NewsRssEntity() {}

    // ✅ 편리한 저장을 위한 생성자
    public NewsRssEntity(String title, String link, String owner, String regDate, 
                         LocalDateTime rawDate, String serverStatus, String featureOption) {
        this.title = title;
        this.link = link;
        this.owner = owner;
        this.regDate = regDate;
        this.rawDate = rawDate;
        this.serverStatus = serverStatus;
        this.featureOption = featureOption;
    }

    // ✅ Getter 메서드들
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public String getOwner() { return owner; }
    public String getRegDate() { return regDate; }
    public LocalDateTime getRawDate() { return rawDate; }
    public String getServerStatus() { return serverStatus; }
    public String getFeatureOption() { return featureOption; }
}