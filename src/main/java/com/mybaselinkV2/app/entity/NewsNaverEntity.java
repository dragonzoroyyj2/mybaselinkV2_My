package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_naver_cache")
public class NewsNaverEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    
    @Column(unique = true) // ✅ 중복 저장 방지
    private String link;
    
    private String owner;
    private String regDate;
    private LocalDateTime rawDate;
    private String serverStatus;
    private String featureOption;

    // 기본 생성자
    public NewsNaverEntity() {}

    // 모든 필드 생성자 (Builder 대신 사용)
    public NewsNaverEntity(String title, String link, String owner, String regDate, LocalDateTime rawDate, String serverStatus, String featureOption) {
        this.title = title;
        this.link = link;
        this.owner = owner;
        this.regDate = regDate;
        this.rawDate = rawDate;
        this.serverStatus = serverStatus;
        this.featureOption = featureOption;
    }

    // Getter / Setter
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getLink() { return link; }
    public String getOwner() { return owner; }
    public String getRegDate() { return regDate; }
    public LocalDateTime getRawDate() { return rawDate; }
    public String getServerStatus() { return serverStatus; }
    public String getFeatureOption() { return featureOption; }

    public void setTitle(String title) { this.title = title; }
    public void setLink(String link) { this.link = link; }
    public void setOwner(String owner) { this.owner = owner; }
    public void setRegDate(String regDate) { this.regDate = regDate; }
    public void setRawDate(LocalDateTime rawDate) { this.rawDate = rawDate; }
    public void setServerStatus(String serverStatus) { this.serverStatus = serverStatus; }
    public void setFeatureOption(String featureOption) { this.featureOption = featureOption; }
}