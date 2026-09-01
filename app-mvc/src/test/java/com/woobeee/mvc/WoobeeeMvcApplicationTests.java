package com.woobeee.mvc;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.woobeee.core.token.TokenStore;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.auth.service.AuthService;
import com.woobeee.mvc.auth.service.TokenService;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.CommentRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ImportAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
class WoobeeeMvcApplicationTests {
    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private TokenStore tokenStore;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private PostRepository postRepository;

    @MockitoBean
    private CommentRepository commentRepository;

    @MockitoBean
    private LikeRepository likeRepository;

    @MockitoBean
    private JPAQueryFactory jpaQueryFactory;

    @MockitoBean
    private ProjectRepository projectRepository;

    @MockitoBean
    private MilestoneRepository milestoneRepository;

    @MockitoBean
    private TaskRepository taskRepository;

    @Test
    void contextLoads() {
    }
}
