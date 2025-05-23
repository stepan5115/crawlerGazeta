package org.example.crawler.entities;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Set;

@Data
@Entity
@Table(name = "categories", uniqueConstraints = {
        @UniqueConstraint(columnNames = "name")
})
//class that represent category table
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @OneToMany(mappedBy = "category")
    private Set<News> news;

    public Category() {
    }
    public Category(String name) {
        this.name = name;
    }
}