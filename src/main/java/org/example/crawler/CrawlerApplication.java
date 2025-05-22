package org.example.crawler;

import org.example.crawler.entities.CrawlLog;
import org.example.crawler.repositories.CrawlLogRepository;
import org.example.crawler.services.NewsCrawlerService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class CrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlerApplication.class, args);
    }

    @Bean
    public CommandLineRunner runCrawler(NewsCrawlerService crawlerService,
                                        CrawlLogRepository crawlLogRepository) {
        return args -> {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

            Runnable crawlerTask = () -> {
                try {
                    // Проверяем последнее обновление
                    Optional<CrawlLog> lastCrawl = crawlLogRepository.findTopByOrderByCrawlTimeDesc();

                    if (lastCrawl.isEmpty() ||
                            ChronoUnit.HOURS.between(lastCrawl.get().getCrawlTime(), LocalDateTime.now()) >= 1) {

                        System.out.println("Запуск краулера...");
                        crawlerService.crawlNews();
                    } else {
                        System.out.println("Последнее обновление было менее часа назад. Пропускаем...");
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка в краулере: " + e.getMessage());
                }
            };

            // Запускаем проверку каждые 10 минут
            executor.scheduleAtFixedRate(crawlerTask, 0, 10, TimeUnit.MINUTES);
        };
    }
}