package com.woobeee.mvc.blog.exception;

public enum ErrorCode {
    signIn_GoogleTokenNotValid,
    signIn_userConflict,
    signUp_userConflict,
    signUp_tagNotFound,
    login_userNotFound,
    login_passwordNotMatch,

    comment_needAuthentication,
    like_needAuthentication,
    post_notFound,
    post_imageUploadError,
    post_imageNotFound,

    api_idempotencyKeyConflictFuckYouStopTryingToMessWithMyServer,
    api_idempotencyKeyConflict
}
