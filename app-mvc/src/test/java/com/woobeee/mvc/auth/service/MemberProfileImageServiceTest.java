package com.woobeee.mvc.auth.service;

import com.woobeee.mvc._common.storage.StorageProperties;
import com.woobeee.mvc.auth.api.request.MemberProfileImagePresignedUrlRequest;
import com.woobeee.mvc.auth.api.request.MemberProfileImageRegisterRequest;
import com.woobeee.mvc.auth.api.response.MemberProfileImageResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileImageUploadUrlResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberProfileImageServiceTest {
    private static final String LOGIN_ID = "member@example.com";

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private StorageProperties storageProperties;
    private MemberProfileImageService memberProfileImageService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setBucket("woobeee");
        storageProperties.setPresignedUrlExpirationSeconds(600);

        memberProfileImageService = new MemberProfileImageService(
                memberRepository,
                s3Client,
                s3Presigner,
                storageProperties
        );
    }

    private Member member(Long id, String profileImageKey) {
        Member member = Member.create("google-sub", LOGIN_ID, "nick", true, true);
        ReflectionTestUtils.setField(member, "id", id);
        member.changeProfileImageKey(profileImageKey);
        return member;
    }

    private void stubPresignPut(String url) {
        PresignedPutObjectRequest presigned = org.mockito.Mockito.mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(toUrl(url));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    private void stubPresignGet(String url) {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(toUrl(url));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
    }

    private static java.net.URL toUrl(String url) {
        try {
            return URI.create(url).toURL();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** AUTH-AC-10 */
    @Test
    void createPresignedUploadUrlReturnsRequesterPrefixedKeyAndTtl() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));
        stubPresignPut("https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=1");

        MemberProfileImageUploadUrlResponse response = memberProfileImageService.createPresignedUploadUrl(
                LOGIN_ID,
                new MemberProfileImagePresignedUrlRequest("../../avatar.png", "image/png")
        );

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());

        assertThat(response.fileKey()).startsWith("profiles/42/");
        assertThat(response.fileKey()).endsWith("/avatar.png");
        assertThat(response.expiresInSeconds()).isEqualTo(600L);
        assertThat(response.uploadUrl()).isEqualTo("https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=1");

        PutObjectPresignRequest presignRequest = captor.getValue();
        assertThat(presignRequest.signatureDuration()).isEqualTo(Duration.ofSeconds(600));
        assertThat(presignRequest.putObjectRequest().bucket()).isEqualTo("woobeee");
        assertThat(presignRequest.putObjectRequest().key()).isEqualTo(response.fileKey());
        assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo("image/png");
    }

    /** AUTH-AC-11 */
    @Test
    void createPresignedUploadUrlRejectsContentTypeOutsideWhitelist() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.createPresignedUploadUrl(
                LOGIN_ID,
                new MemberProfileImagePresignedUrlRequest("payload.svg", "image/svg+xml")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(s3Presigner);
    }

    /** AUTH-AC-12 */
    @Test
    void registerRejectsFileKeyOfAnotherMember() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.register(
                LOGIN_ID,
                new MemberProfileImageRegisterRequest("profiles/43/uuid/avatar.png")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(memberRepository, never()).save(any(Member.class));
        verifyNoInteractions(s3Client);
    }

    /** AUTH-AC-13 */
    @Test
    void registerUpdatesColumnAndDeletesPreviousObject() {
        Member member = member(42L, "profiles/42/old-uuid/old.png");
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);
        stubPresignGet("https://s3.example.com/woobeee/profiles/42/new-uuid/new.png?sig=2");

        MemberProfileImageResponse response = memberProfileImageService.register(
                LOGIN_ID,
                new MemberProfileImageRegisterRequest("profiles/42/new-uuid/new.png")
        );

        assertThat(member.getProfileImageKey()).isEqualTo("profiles/42/new-uuid/new.png");
        assertThat(response.profileImageUrl())
                .isEqualTo("https://s3.example.com/woobeee/profiles/42/new-uuid/new.png?sig=2");

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("woobeee");
        assertThat(deleteCaptor.getValue().key()).isEqualTo("profiles/42/old-uuid/old.png");
    }

    @Test
    void registerSurvivesFailureToDeletePreviousObject() {
        Member member = member(42L, "profiles/42/old-uuid/old.png");
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("storage is down"));
        stubPresignGet("https://s3.example.com/woobeee/profiles/42/new-uuid/new.png?sig=2");

        MemberProfileImageResponse response = memberProfileImageService.register(
                LOGIN_ID,
                new MemberProfileImageRegisterRequest("profiles/42/new-uuid/new.png")
        );

        assertThat(member.getProfileImageKey()).isEqualTo("profiles/42/new-uuid/new.png");
        assertThat(response.profileImageUrl()).isNotBlank();
    }

    @Test
    void deleteClearsColumnAndRemovesObject() {
        Member member = member(42L, "profiles/42/uuid/avatar.png");
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);

        memberProfileImageService.delete(LOGIN_ID);

        assertThat(member.getProfileImageKey()).isNull();

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().key()).isEqualTo("profiles/42/uuid/avatar.png");
    }

    @Test
    void deleteIsNoOpWhenProfileImageIsNotSet() {
        Member member = member(42L, null);
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));

        memberProfileImageService.delete(LOGIN_ID);

        verifyNoInteractions(s3Client);
        verify(memberRepository, never()).save(any(Member.class));
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReturnsPresignedGetUrl() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, "profiles/42/uuid/avatar.png")));
        stubPresignGet("https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=3");

        MemberProfileResponse response = memberProfileImageService.getMyProfile(LOGIN_ID);

        assertThat(response.memberId()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo(LOGIN_ID);
        assertThat(response.nickname()).isEqualTo("nick");
        assertThat(response.gameMoney()).isZero();
        assertThat(response.profileImageUrl())
                .isEqualTo("https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=3");
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReturnsNullProfileImageUrlWhenUnset() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        MemberProfileResponse response = memberProfileImageService.getMyProfile(LOGIN_ID);

        assertThat(response.profileImageUrl()).isNull();
        verifyNoInteractions(s3Presigner);
    }

    @Test
    void unknownLoginIdIsRejectedAsUnauthorized() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberProfileImageService.getMyProfile(LOGIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void blankLoginIdIsRejectedAsUnauthorized() {
        assertThatThrownBy(() -> memberProfileImageService.getMyProfile(" "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(memberRepository);
    }
}
