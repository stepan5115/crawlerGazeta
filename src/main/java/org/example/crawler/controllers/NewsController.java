package org.example.crawler.controllers;

import lombok.RequiredArgsConstructor;
import org.example.crawler.entities.News;
import org.example.crawler.repositories.NewsRepository;
import org.example.crawler.services.NewsCrawlerService;
import org.example.crawler.services.NewsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController {
    private final NewsService newsService;
    private final NewsCrawlerService newsCrawlerService;
    private final NewsRepository newsRepository;

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
        News news = newsCrawlerService.processNews(url);
        if (news == null) {
            return ResponseEntity.ok(Map.of("result", "WRONG: can't parse news on url: " + url));
        }
        Optional<News> oldNewsOptional = newsRepository.findByUrl(url);
        if (oldNewsOptional.isPresent()) {
            News oldNews = oldNewsOptional.get();
            news.setId(oldNews.getId());
            if (!(oldNews.getCategory().getId().equals(news.getCategory().getId())) ||
                    !(oldNews.getTitle().equals(news.getTitle())) ||
                    !(oldNews.getContent().equals(news.getContent())) ||
                    !(oldNews.getAuthor().getId().equals(news.getAuthor().getId())) ||
                    !(oldNews.getPublicationDate().equals(news.getPublicationDate())))
            {
                newsRepository.save(news);
                return ResponseEntity.ok(Map.of("result", String.format("News updated: %s", news.getUrl())));
            }
            else {
                oldNews.setCreatedAt(LocalDateTime.now());
                newsRepository.save(oldNews);
                return ResponseEntity.ok(Map.of("result", String.format("News up to date: %s", news.getUrl())));
            }
        }
        else {
            newsRepository.save(news);
            return ResponseEntity.ok(Map.of("result", "OK"));
        }
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