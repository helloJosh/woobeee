package com.woobeee.mvc.blog.api.batch;


import java.time.LocalDateTime;

public record PostExportDto(
        Long id,
        String url,
        String titleKo,
        String titleEn,
        String textKo,
        String textEn,
        Long views,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String categoryNameKo,
        String categoryNameEn
) {}