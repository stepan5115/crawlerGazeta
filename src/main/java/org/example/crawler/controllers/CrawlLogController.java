package org.example.crawler.controllers;

import lombok.RequiredArgsConstructor;
import org.example.crawler.entities.CrawlLog;
import org.example.crawler.repositories.CrawlLogRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/crawl-log")
@RequiredArgsConstructor
public class CrawlLogController {
    private final CrawlLogRepository crawlLogRepository;

    @GetMapping
    public ResponseEntity<List<CrawlLog>> getCrawlLogs(
            @RequestParam(defaultValue = "10") int limit) {

        List<CrawlLog> logs = crawlLogRepository.findTop10ByOrderByCrawlTimeDesc()
                .stream()
                .limit(limit)
                .toList();

        return ResponseEntity.ok(logs);
    }
}