package com.woobeee.mvc.blog.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "likes",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_likes_member_post",
                        columnNames = {"member_id", "member_role", "post_id"}
                )
        }
)
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class Likes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "member_role")
    private String memberRole;

    @Column(name = "post_id")
    private Long postId;

    @CreationTimestamp
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public Likes(Long memberId, String memberRole, Long postId) {
        this.memberId = memberId;
        this.memberRole = memberRole;
        this.postId = postId;
    }
}
