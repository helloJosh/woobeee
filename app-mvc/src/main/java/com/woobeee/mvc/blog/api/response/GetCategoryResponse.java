package com.woobeee.mvc.blog.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GetCategoryResponse(
        Long id,
        String name,
        Integer count,
        List<GetCategoryResponse> children
) {
}