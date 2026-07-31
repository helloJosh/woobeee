package com.woobeee.mvc.blog.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Builder
public record GetCommentResponse(
        Long id,
        String author,
        Boolean isEditable,
        String content,
        LocalDateTime createdAt,
        List<GetCommentResponse> replies
) {
}