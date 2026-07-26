package com.shivaxdev.shrinkr.repository;

import com.shivaxdev.shrinkr.model.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Modifying
    @Transactional
    @Query("UPDATE ShortLink l SET l.clickCount = l.clickCount + :count WHERE l.slug = :slug")
    int incrementClickCount(@Param("slug") String slug, @Param("count") int count);

    List<ShortLink> findTop25ByScanStatusAndCreatedAtBefore(String scanStatus, LocalDateTime cutoff);
}
