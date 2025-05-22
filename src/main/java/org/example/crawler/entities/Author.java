package org.example.crawler.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@Entity
@Table(name = "authors")
public class Author {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String email;

    @OneToMany(mappedBy = "author")
    private Set<News> news;

    public Author() {
    }

    public Author(String name, String email) {
        this.name = name;
        this.email = email;
    }
}