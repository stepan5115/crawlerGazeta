package org.example.crawler.repositories;

import org.example.crawler.entities.CrawlLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

//class that need to work with crawler log table
public interface CrawlLogRepository extends JpaRepository<CrawlLog, Long> {
    List<CrawlLog> findTop10ByOrderByCrawlTimeDesc();

    Optional<CrawlLog> findTopByOrderByCrawlTimeDesc();

    Optional<CrawlLog> findFirstByOrderByCrawlTimeDesc();
}