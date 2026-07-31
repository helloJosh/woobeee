package com.woobeee.mvc.auth.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.woobeee.mvc.auth.service.dto.GoogleIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GoogleIdTokenVerifierClient implements GoogleIdentityVerifier {
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenVerifierClient(
            @Value("${oauth.google.client-id}") String googleClientId
    ) throws GeneralSecurityException, IOException {
        this.verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance()
        )
                .setAudience(List.of(googleClientId))
                .build();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (!StringUtils.hasText(idToken)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google ID token is required");
        }

        try {
            GoogleIdToken googleIdToken = verifier.verify(idToken);
            if (googleIdToken == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token");
            }

            GoogleIdToken.Payload payload = googleIdToken.getPayload();
            if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google email is not verified");
            }
            if (!StringUtils.hasText(payload.getSubject()) || !StringUtils.hasText(payload.getEmail())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google account payload is invalid");
            }

            Object name = payload.get("name");
            return new GoogleIdentity(
                    payload.getSubject(),
                    payload.getEmail(),
                    name == null ? null : name.toString()
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to verify Google ID token",
                    exception
            );
        } catch (GeneralSecurityException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Google ID token verification failed",
                    exception
            );
        }
    }
}
