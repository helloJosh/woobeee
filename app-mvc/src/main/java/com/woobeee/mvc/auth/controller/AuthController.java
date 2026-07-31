package com.woobeee.mvc.auth.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.auth.api.request.BuyerSignupRequest;
import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.request.SellerSignupRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth Controller", description = "회원가입 및 로그인 컨트롤러")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/signup/buyers")
    @Operation(summary = "구매자 회원가입 authorization 시작", description = "Authorization Code + PKCE용 Google authorization URL을 발급합니다.")
    public ApiResponse<GoogleAuthorizationResponse> signupBuyer(
            @Valid @RequestBody BuyerSignupRequest request
    ) {
        GoogleAuthorizationResponse response = authService.signupBuyer(request);
        return ApiResponse.success(response, "Buyer signup authorization created");
    }

    @PostMapping("/signup/sellers")
    @Operation(summary = "판매자 회원가입 authorization 시작", description = "Authorization Code + PKCE용 Google authorization URL을 발급합니다.")
    public ApiResponse<GoogleAuthorizationResponse> signupSeller(
            @Valid @RequestBody SellerSignupRequest request
    ) {
        GoogleAuthorizationResponse response = authService.signupSeller(request);
        return ApiResponse.success(response, "Seller signup authorization created");
    }

    @PostMapping("/login")
    @Operation(summary = "로그인 authorization 시작", description = "Authorization Code + PKCE용 Google authorization URL을 발급합니다.")
    public ApiResponse<GoogleAuthorizationResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        GoogleAuthorizationResponse response = authService.login(request);
        return ApiResponse.success(response, "Login authorization created");
    }

    @PostMapping("/callback-google")
    @Operation(summary = "Google authorization callback 처리", description = "Google authorization code와 state를 교환해 access/refresh token을 발급합니다.")
    public ApiResponse<TokenResponse> completeGoogleAuthorization(
            @Valid @RequestBody GoogleAuthorizationCallbackRequest request,
            HttpServletRequest httpServletRequest
    ) {
        TokenResponse response = authService.completeGoogleAuthorization(
                request,
                resolveClientIp(httpServletRequest)
        );
        return ApiResponse.success(response, "Google authorization completed");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return request.getRemoteAddr();
    }
}
