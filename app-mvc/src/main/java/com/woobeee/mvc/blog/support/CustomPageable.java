package com.woobeee.mvc.blog.support;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class CustomPageable implements Pageable {
    private final int page;
    private final int size;

    public CustomPageable(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page index must not be less than zero!");
        if (size < 1) throw new IllegalArgumentException("Page size must not be less than one!");
        this.page = page;
        this.size = size;
    }

    @Override
    public int getPageNumber() {
        return page;
    }

    @Override
    public int getPageSize() {
        return size;
    }

    @Override
    public long getOffset() {
        return (long) page * size;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted(); // 정렬 필요 없으면 그대로
    }

    @Override
    public Pageable next() {
        return new CustomPageable(page + 1, size);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new CustomPageable(page - 1, size) : first();
    }

    @Override
    public Pageable first() {
        return new CustomPageable(0, size);
    }

    @Override
    public boolean hasPrevious() {
        return page > 0;
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new CustomPageable(pageNumber, size);
    }
}
