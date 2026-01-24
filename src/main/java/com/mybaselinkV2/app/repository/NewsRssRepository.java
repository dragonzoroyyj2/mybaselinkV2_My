package com.mybaselinkV2.app.repository;

import com.mybaselinkV2.app.entity.NewsRssEntity; // 엔티티 클래스명 확인 필요
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface NewsRssRepository extends JpaRepository<NewsRssEntity, Long> {
    
    // 🔍 검색 및 중복 체크
    boolean existsByLink(String link);
    boolean existsByTitle(String title);

    // 🧹 3일치 데이터 청소
    @Transactional
    void deleteByRawDateBefore(LocalDateTime dateTime);
}