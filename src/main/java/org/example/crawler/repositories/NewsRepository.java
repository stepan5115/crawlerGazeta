package org.example.crawler.repositories;

import org.example.crawler.entities.News;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

//class that need to work with news table
public interface NewsRepository extends JpaRepository<News, Long> {
    @Query("SELECT n FROM News n WHERE " +
            "(:category IS NULL OR LOWER(n.category.name) LIKE :category) AND " +
            "(:author IS NULL OR LOWER(n.author.name) LIKE :author) AND " +
            "(CAST(:dateFrom AS timestamp) IS NULL OR n.publicationDate >= :dateFrom) AND " +
            "(CAST(:dateTo AS timestamp) IS NULL OR n.publicationDate <= :dateTo)")
    List<News> findFilteredNews(
            @Param("category") String category,
            @Param("author") String author,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo);

    @Query("SELECT n FROM News n WHERE " +
            "LOWER(n.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(n.content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(n.author.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<News> searchNews(@Param("query") String query);

    boolean existsByUrl(String url);

    Optional<News> findByUrl(String url);
}