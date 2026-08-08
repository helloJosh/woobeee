package com.woobeee.mvc.blog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
@Entity
@Table(name = "posts")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class Posts {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String titleKo;
    private String titleEn;

    @Column(columnDefinition = "text")
    @Setter
    private String textKo;
    @Column(columnDefinition = "text")
    @Setter
    private String textEn;

    @Builder.Default
    private Long views = 0L;

    @CreationTimestamp
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @UpdateTimestamp
    private LocalDateTime updatedAt;

//    @ManyToOne(fetch = FetchType.LAZY)
//    private Categories category;
    private Long categoryId;
    private Long memberId;

    public Posts(String titleKo, String titleEn, String textKo, String textEn, Long categoryId, Long memberId) {
        this.titleKo = titleKo;
        this.titleEn = titleEn;
        this.textKo = textKo;
        this.textEn = textEn;
        this.categoryId = categoryId;
        this.memberId = memberId;
        this.views = 0L;
    }

    public void updateContent(String titleKo, String titleEn, String textKo, String textEn, Long categoryId) {
        this.titleKo = titleKo;
        this.titleEn = titleEn;
        if (textKo != null) {
            this.textKo = textKo;
        }
        if (textEn != null) {
            this.textEn = textEn;
        }
        this.categoryId = categoryId;
    }
}
