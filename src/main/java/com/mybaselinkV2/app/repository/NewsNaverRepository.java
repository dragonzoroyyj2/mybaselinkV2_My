package com.mybaselinkV2.app.repository;

import com.mybaselinkV2.app.entity.NewsNaverEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface NewsNaverRepository extends JpaRepository<NewsNaverEntity, Long> {

    // 🔍 검색용: 제목에 키워드 포함 여부
    Page<NewsNaverEntity> findByTitleContaining(String title, Pageable pageable);

    // 🚫 중복 체크용: 링크 또는 제목 존재 확인
    boolean existsByLink(String link);
    boolean existsByTitle(String title);

    // 🧹 청소용: 3일 이전 데이터 삭제 (삭제는 트랜잭션 필수)
    @Transactional
    void deleteByRawDateBefore(LocalDateTime dateTime);
}