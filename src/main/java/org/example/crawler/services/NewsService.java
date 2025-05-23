package org.example.crawler.services;

import lombok.RequiredArgsConstructor;
import org.example.crawler.entities.News;
import org.example.crawler.repositories.NewsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NewsService {
    private final NewsRepository newsRepository;

    public List<News> getFilteredNews(String category, String author,
                                      LocalDateTime dateFrom, LocalDateTime dateTo,
                                      int limit) {
        if (category != null)
            category = "%" + category.toLowerCase() + "%";
        if (author != null)
            author = "%" + author.toLowerCase() + "%";
        return newsRepository.findFilteredNews(category, author, dateFrom, dateTo)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public News getNewsById(Long id) {
        return newsRepository.findById(id).orElse(null);
    }

    public List<News> searchNews(String query, int limit) {
        return newsRepository.searchNews(query)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public Map<String, Long> getNewsStats(String period) {
        // Реализация статистики по периодам
        // В реальном проекте нужно добавить реализацию
        return Map.of();
    }

    public boolean deleteNews(Long id) {
        if (newsRepository.existsById(id)) {
            newsRepository.deleteById(id);
            return true;
        }
        return false;
    }
}