package com.woobeee.mvc.blog.service;


public interface LikeService {
    void saveLike(Long postId, String userId);
    void deleteLike(Long postId, String userId);
}
