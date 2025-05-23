package org.example.crawler.repositories;

import org.example.crawler.entities.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

//class that need to work with category table
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
}