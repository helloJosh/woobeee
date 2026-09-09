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

    /** 한 줄 설명 — 제목처럼 언어별, 선택 (BLOG-AC-23). 빈 값은 null 로 정규화한다. */
    @Column(length = 300)
    private String descriptionKo;

    @Column(length = 300)
    private String descriptionEn;

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

    /** 빈 문자열은 null 로 — "없음" 을 한 가지로만 표현한다. */
    public void updateDescription(String descriptionKo, String descriptionEn) {
        this.descriptionKo = descriptionKo == null || descriptionKo.isBlank() ? null : descriptionKo.trim();
        this.descriptionEn = descriptionEn == null || descriptionEn.isBlank() ? null : descriptionEn.trim();
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
