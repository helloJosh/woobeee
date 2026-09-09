package com.woobeee.mvc.blog.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 글 태그 한 개 (BLOG-AC-18). 이름 비교는 소문자로, 표기는 처음 저장된 그대로 (V13 의 lower(name) 유니크). */
@Getter
@Entity
@Table(name = "tags")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tags {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String name;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Tags(String name) {
        this.name = name;
    }

    public static Tags create(String name) {
        return new Tags(name);
    }
}
