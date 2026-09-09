package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.exception.CustomBadRequestException;
import com.woobeee.mvc.blog.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BLOG-AC-18 — 태그 입력 정규화: trim, 빈 값 제거, 대소문자 무시 중복 제거, 최대 10개, 각 1~30자. */
class TagNormalizerTest {

    @Test
    void trimsDropsBlanksAndDedupesIgnoringCaseKeepingTheFirstSpelling() {
        List<String> out = TagNormalizer.normalize(Arrays.asList(" Spring ", "", "  ", "spring", "Kafka", null, "KAFKA"));

        assertThat(out).containsExactly("Spring", "Kafka");
    }

    @Test
    void nullOrEmptyInputIsAnEmptyList() {
        assertThat(TagNormalizer.normalize(null)).isEmpty();
        assertThat(TagNormalizer.normalize(Collections.emptyList())).isEmpty();
    }

    @Test
    void moreThanTenDistinctTagsAreRejected() {
        List<String> eleven = java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i -> "t" + i).toList();

        assertThatThrownBy(() -> TagNormalizer.normalize(eleven))
                .isInstanceOf(CustomBadRequestException.class)
                .hasMessage(ErrorCode.post_invalidTags.name());
        assertThat(TagNormalizer.normalize(eleven.subList(0, 10))).hasSize(10);
    }

    @Test
    void aTagLongerThanThirtyCharactersIsRejected() {
        assertThatThrownBy(() -> TagNormalizer.normalize(List.of("a".repeat(31))))
                .isInstanceOf(CustomBadRequestException.class);
        assertThat(TagNormalizer.normalize(List.of("a".repeat(30)))).containsExactly("a".repeat(30));
    }
}
