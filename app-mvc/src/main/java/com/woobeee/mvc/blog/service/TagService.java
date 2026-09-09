package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.response.GetTagResponse;

import java.util.List;

public interface TagService {
    /** 글 수 내림차순 상위 limit 개 (BLOG-AC-22). */
    List<GetTagResponse> getPopular(int limit);
}
