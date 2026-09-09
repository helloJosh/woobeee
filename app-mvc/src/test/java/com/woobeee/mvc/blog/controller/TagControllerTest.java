package com.woobeee.mvc.blog.controller;

import com.woobeee.mvc.blog.api.response.GetTagResponse;
import com.woobeee.mvc.blog.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock TagService tagService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TagController(tagService)).build();
    }

    /** BLOG-AC-22 — 인기 태그는 공개 GET 이고 limit 기본값은 20 이다. */
    @Test
    void popularTagsAreWrappedInTheEnvelopeWithADefaultLimitOfTwenty() throws Exception {
        when(tagService.getPopular(20)).thenReturn(List.of(new GetTagResponse(1L, "Spring", 3)));

        mockMvc.perform(get("/api/back/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Spring"))
                .andExpect(jsonPath("$.data[0].count").value(3));
    }

    @Test
    void limitIsPassedThrough() throws Exception {
        when(tagService.getPopular(5)).thenReturn(List.of());

        mockMvc.perform(get("/api/back/tags").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
