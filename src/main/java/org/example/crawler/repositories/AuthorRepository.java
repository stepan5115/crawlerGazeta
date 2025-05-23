package org.example.crawler.repositories;

import org.example.crawler.entities.Author;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

//class that need to work with author table
public interface AuthorRepository extends JpaRepository<Author, Long> {
    Optional<Author> findByName(String name);
}