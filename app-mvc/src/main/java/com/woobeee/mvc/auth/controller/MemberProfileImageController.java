package com.woobeee.mvc.auth.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.auth.api.request.MemberProfileImagePresignedUrlRequest;
import com.woobeee.mvc.auth.api.request.MemberProfileImageRegisterRequest;
import com.woobeee.mvc.auth.api.response.MemberProfileImageResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileImageUploadUrlResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.service.MemberProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Member Profile Image Controller", description = "내 프로필 및 프로필 이미지 컨트롤러")
@RequiredArgsConstructor
public class MemberProfileImageController {
    private static final String LOGIN_ID_HEADER = "loginId";

    private final MemberProfileImageService memberProfileImageService;

    @GetMapping("/me")
    @Operation(summary = "내 프로필 조회", description = "회원 정보와 프로필 이미지 presigned GET URL을 반환합니다.")
    public ApiResponse<MemberProfileResponse> getMyProfile(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId
    ) {
        MemberProfileResponse response = memberProfileImageService.getMyProfile(loginId);
        return ApiResponse.success(response, "Member profile retrieved");
    }

    @PostMapping("/me/profile-image/presigned-url")
    @Operation(summary = "프로필 이미지 업로드 URL 발급", description = "S3에 직접 업로드할 presigned PUT URL을 발급합니다.")
    public ApiResponse<MemberProfileImageUploadUrlResponse> createPresignedUploadUrl(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId,
            @Valid @RequestBody MemberProfileImagePresignedUrlRequest request
    ) {
        MemberProfileImageUploadUrlResponse response =
                memberProfileImageService.createPresignedUploadUrl(loginId, request);
        return ApiResponse.success(response, "Profile image upload url created");
    }

    @PutMapping("/me/profile-image")
    @Operation(summary = "프로필 이미지 등록/교체", description = "업로드한 fileKey를 프로필 이미지로 등록하고 이전 오브젝트를 삭제합니다.")
    public ApiResponse<MemberProfileImageResponse> register(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId,
            @Valid @RequestBody MemberProfileImageRegisterRequest request
    ) {
        MemberProfileImageResponse response = memberProfileImageService.register(loginId, request);
        return ApiResponse.success(response, "Profile image registered");
    }

    @DeleteMapping("/me/profile-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "프로필 이미지 삭제", description = "프로필 이미지를 해제하고 오브젝트를 삭제합니다.")
    public void delete(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId
    ) {
        memberProfileImageService.delete(loginId);
    }
}
