package com.mybaselinkV2.app.repository;

import com.mybaselinkV2.app.entity.NewsDartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsDartRepository extends JpaRepository<NewsDartEntity, Long> {

    // 🚩 중복 저장 방지용
    boolean existsByLink(String link);

    // 🚩 [수정] Pageable을 제거하고 List를 리턴하도록 변경 (형님의 서비스 로직 맞춤)
    List<NewsDartEntity> findByTitleContainingOrOwnerContaining(String title, String owner);

    // 🚩 DB 청소용
    void deleteByRawDateBefore(LocalDateTime dateTime);
}