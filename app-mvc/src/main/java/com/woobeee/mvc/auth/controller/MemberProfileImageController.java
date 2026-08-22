package com.woobeee.mvc.auth.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.service.MemberProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Member Profile Image Controller", description = "내 프로필 및 프로필 이미지 컨트롤러")
@RequiredArgsConstructor
public class MemberProfileImageController {
    private static final String LOGIN_ID_HEADER = "loginId";

    private final MemberProfileImageService memberProfileImageService;

    @GetMapping("/me")
    @Operation(summary = "내 프로필 조회", description = "회원 정보와 프로필 이미지 보유 여부를 반환합니다.")
    public ApiResponse<MemberProfileResponse> getMyProfile(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId
    ) {
        MemberProfileResponse response = memberProfileImageService.getMyProfile(loginId);
        return ApiResponse.success(response, "Member profile retrieved");
    }

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "프로필 이미지 업로드/교체", description = "이미지를 업로드해 프로필로 등록하고 이전 오브젝트를 삭제합니다.")
    public ApiResponse<MemberProfileResponse> upload(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId,
            @RequestPart("file") MultipartFile file
    ) {
        MemberProfileResponse response = memberProfileImageService.upload(loginId, file);
        return ApiResponse.success(response, "Profile image uploaded");
    }

    /**
     * 프로필 이미지 스트리밍. 버킷을 공개하지 않고 앱이 자격증명으로 대신 읽어 준다.
     *
     * <p>{@code ApiResponse} 봉투를 태우지 않는다 — 바이트와 contentType 이 그대로 나가야 한다.
     * 본인 전용 리소스이므로 캐시는 {@code no-store} 다. 프론트가 blob URL 로 들고 있으므로
     * 재요청 시점은 프론트가 정한다(업로드·삭제 직후).
     */
    @GetMapping("/me/profile-image")
    @Operation(summary = "프로필 이미지 조회", description = "내 프로필 이미지 바이트를 반환합니다. 미설정이면 404입니다.")
    public ResponseEntity<byte[]> getMyProfileImage(
            @RequestHeader(value = LOGIN_ID_HEADER, required = false) String loginId
    ) {
        MemberProfileImageService.ProfileImage image = memberProfileImageService.loadMyProfileImage(loginId);

        return ResponseEntity.ok()
                .contentType(image.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(image.bytes());
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
