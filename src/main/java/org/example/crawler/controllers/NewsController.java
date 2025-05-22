package org.example.crawler.controllers;

import lombok.RequiredArgsConstructor;
import org.example.crawler.entities.News;
import org.example.crawler.services.NewsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController {
    private final NewsService newsService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNews(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(defaultValue = "20") int limit) {

        List<News> news = newsService.getFilteredNews(category, author, dateFrom, dateTo, limit);
        return ResponseEntity.ok(Map.of(
                "count", news.size(),
                "results", news
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<News> getNewsById(@PathVariable Long id) {
        News news = newsService.getNewsById(id);
        if (news == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(news);
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchNews(
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {

        List<News> news = newsService.searchNews(query, limit);
        return ResponseEntity.ok(Map.of(
                "count", news.size(),
                "results", news
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> addNews(
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestParam String url) {

        if (!"secret-api-key".equals(apiKey)) {
            return ResponseEntity.status(403).build();
        }

        // В реальном проекте нужно добавить логику добавления новости
        return ResponseEntity.ok(Map.of("result", "OK"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteNews(
            @RequestHeader("X-API-KEY") String apiKey,
            @PathVariable Long id) {

        if (!"secret-api-key".equals(apiKey)) {
            return ResponseEntity.status(403).build();
        }

        if (newsService.deleteNews(id)) {
            return ResponseEntity.ok(Map.of("result", "OK"));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(defaultValue = "month") String period) {

        Map<String, Long> stats = newsService.getNewsStats(period);
        return ResponseEntity.ok(Map.of("stats", stats));
    }
}