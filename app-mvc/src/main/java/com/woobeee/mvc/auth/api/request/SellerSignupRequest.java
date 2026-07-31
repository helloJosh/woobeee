package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SellerSignupRequest(
        @NotBlank(message = "Nickname is required")
        @Size(max = 60, message = "Nickname must be 60 characters or fewer")
        String nickname,
        @AssertTrue(message = "Terms agreement is required")
        boolean termsAgreed,
        @AssertTrue(message = "Privacy policy agreement is required")
        boolean privacyPolicyAgreed,
        @Size(max = 1000, message = "Business registration certificate URL must be 1000 characters or fewer")
        String businessRegistrationCertificateUrl,
        @NotBlank(message = "Device is required")
        String device
) {
}
