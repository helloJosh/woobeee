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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.WebRequest;
import java.time.Duration;
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
     * 프로필 이미지 스트리밍. <b>인증하지 않는다 -- 누구나 볼 수 있다.</b>
     *
     * <p>{@code /me} 가 아니라 회원 id 로 받는 이유가 이 변경의 요점이다. 전에는 인증이 필요한
     * {@code GET /me/profile-image} 뿐이어서 <b>남의 아바타를 그릴 방법이 없었다</b> --
     * {@code <img>} 는 Authorization 헤더를 못 보내므로 프론트가 fetch 로 받아 blob URL 을
     * 만들어야 했고, 그건 본인 것에만 가능했다. 댓글 작성자 아바타를 붙이려면 공개여야 한다.
     *
     * <p>{@code ApiResponse} 봉투를 태우지 않는다 -- {@code <img>} 가 여는 주소이므로 바이트와
     * contentType 이 그대로 나가야 한다.
     *
     * <p>ETag 는 오브젝트 키에서 파생한다. 이미지를 교체하면 키의 UUID 가 바뀌므로 값도 바뀌고,
     * 브라우저는 {@code If-None-Match} 로 재검증해 안 바뀌었으면 304 를 받는다. URL 자체는
     * 고정이므로(presigned URL 과 달리) 캐시가 실제로 쓰인다.
     */
    @Operation(summary = "프로필 이미지 조회(공개)", description = "회원의 프로필 이미지 바이트를 반환합니다. 미설정이면 404입니다.")
    @GetMapping("/members/{memberId}/profile-image")
    public ResponseEntity<byte[]> getProfileImage(
            @PathVariable("memberId") Long memberId,
            WebRequest webRequest
    ) {
        MemberProfileImageService.ProfileImage image = memberProfileImageService.loadProfileImage(memberId);

        if (webRequest.checkNotModified(image.eTag())) {
            return null;
        }

        return ResponseEntity.ok()
                .contentType(image.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(image.contentType()))
                .eTag("\"" + image.eTag() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
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
