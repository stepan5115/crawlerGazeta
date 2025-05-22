package org.example.crawler.entities;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "crawl_log")
public class CrawlLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime crawlTime;

    @Column
    private Integer newNewsCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}