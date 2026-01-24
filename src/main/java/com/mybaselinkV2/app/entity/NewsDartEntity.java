package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_dart_cache")
public class NewsDartEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String title;     // report_nm (공시제목)

    @Column(length = 1000, nullable = false)
    private String link;      // rcept_no 기반 링크

    @Column(length = 100)
    private String owner;     // corp_name (회사명)

    @Column(length = 50)
    private String regDate;   // rcept_dt (접수일)

    @Column(nullable = false)
    private LocalDateTime rawDate; // 정렬 및 삭제용 (현재시간 기준)

    @Column(length = 50)
    private String serverStatus;   // 시장구분 (코스피/코스닥 등)

    @Column(length = 100)
    private String featureOption;  // 재무상태 (흑자/적자)

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public NewsDartEntity() {}

    public NewsDartEntity(String title, String link, String owner, String regDate, 
                          LocalDateTime rawDate, String serverStatus, String featureOption) {
        this.title = title;
        this.link = link;
        this.owner = owner;
        this.regDate = regDate;
        this.rawDate = rawDate;
        this.serverStatus = serverStatus;
        this.featureOption = featureOption;
    }

    // Getter 생략 (형님 스타일대로 추가하세요)
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public String getOwner() { return owner; }
    public String getRegDate() { return regDate; }
    public LocalDateTime getRawDate() { return rawDate; }
    public String getServerStatus() { return serverStatus; }
    public String getFeatureOption() { return featureOption; }
}