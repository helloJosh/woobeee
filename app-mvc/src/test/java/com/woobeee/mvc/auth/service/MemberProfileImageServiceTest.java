package com.woobeee.mvc.auth.service;

import com.woobeee.mvc._common.storage.StorageProperties;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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

    private StorageProperties storageProperties;
    private MemberProfileImageService memberProfileImageService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setBucket("woobeee");

        memberProfileImageService = new MemberProfileImageService(
                memberRepository,
                s3Client,
                storageProperties
        );
    }

    private Member member(Long id, String profileImageKey) {
        Member member = Member.create("google-sub", LOGIN_ID, "nick", true, true);
        ReflectionTestUtils.setField(member, "id", id);
        member.changeProfileImageKey(profileImageKey);
        return member;
    }

    private MultipartFile png(String fileName, int byteCount) {
        return new MockMultipartFile("file", fileName, "image/png", new byte[byteCount]);
    }

    /**
     * AUTH-AC-10 — 업로드는 {@code profiles/{memberId}/{uuid}/{파일명}} 키로 저장한다.
     * memberId 는 요청이 아니라 <b>토큰에서 온 회원</b>이어야 한다 — 요청 값을 믿으면 남의
     * 프로필에 오브젝트를 붙일 수 있다.
     */
    @Test
    void uploadStoresTheObjectUnderTheRequesterPrefix() {
        Member member = member(42L, null);
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);

        memberProfileImageService.upload(LOGIN_ID, png("../../avatar.png", 10));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        assertThat(captor.getValue().bucket()).isEqualTo("woobeee");
        assertThat(captor.getValue().key()).startsWith("profiles/42/");
        assertThat(captor.getValue().key()).endsWith("/avatar.png");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
        assertThat(member.getProfileImageKey()).isEqualTo(captor.getValue().key());
    }

    /** AUTH-AC-11 — 허용 목록 밖 contentType 은 400 이고 스토리지에 손대지 않는다. */
    @Test
    void uploadRejectsContentTypeOutsideWhitelist() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.upload(
                LOGIN_ID,
                new MockMultipartFile("file", "payload.svg", "image/svg+xml", new byte[10])
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(s3Client);
        verify(memberRepository, never()).save(any(Member.class));
    }

    /**
     * AUTH-AC-12 — 5MB 를 넘으면 400.
     *
     * <p>업로드가 앱을 거치므로(presigned 직결이 아니다) 상한이 없으면 큰 파일이 앱 힙을
     * 그대로 받는다. 경계 위/아래를 함께 고정한다.
     */
    @Test
    void uploadRejectsFilesLargerThanFiveMegabytes() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.upload(LOGIN_ID, png("big.png", 5 * 1024 * 1024 + 1)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(s3Client);
    }

    /** AUTH-AC-12 — 정확히 5MB 는 통과한다(경계는 포함). */
    @Test
    void uploadAcceptsAFileExactlyAtTheLimit() {
        Member member = member(42L, null);
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);

        memberProfileImageService.upload(LOGIN_ID, png("exact.png", 5 * 1024 * 1024));

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 빈 파일은 400 — 0바이트 오브젝트를 프로필로 붙이면 조회가 깨진 이미지가 된다. */
    @Test
    void uploadRejectsAnEmptyFile() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.upload(LOGIN_ID, png("empty.png", 0)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(s3Client);
    }

    /** AUTH-AC-13 — 교체가 성공하면 컬럼을 갱신하고 이전 오브젝트를 삭제한다. */
    @Test
    void uploadReplacesTheColumnAndDeletesThePreviousObject() {
        Member member = member(42L, "profiles/42/old-uuid/old.png");
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);

        MemberProfileResponse response = memberProfileImageService.upload(LOGIN_ID, png("new.png", 10));

        assertThat(member.getProfileImageKey()).endsWith("/new.png");
        assertThat(member.getProfileImageKey()).isNotEqualTo("profiles/42/old-uuid/old.png");
        assertThat(response.hasProfileImage()).isTrue();

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("woobeee");
        assertThat(deleteCaptor.getValue().key()).isEqualTo("profiles/42/old-uuid/old.png");
    }

    /**
     * 이전 오브젝트 삭제가 실패해도 교체는 성공으로 남는다. 순서가 요점이다 — 저장을 커밋한
     * 뒤에 지우므로, 삭제 실패는 고아 오브젝트만 남기고 프로필을 깨뜨리지 않는다.
     */
    @Test
    void uploadSurvivesFailureToDeleteThePreviousObject() {
        Member member = member(42L, "profiles/42/old-uuid/old.png");
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member));
        when(memberRepository.save(member)).thenReturn(member);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("storage is down"));

        MemberProfileResponse response = memberProfileImageService.upload(LOGIN_ID, png("new.png", 10));

        assertThat(member.getProfileImageKey()).endsWith("/new.png");
        assertThat(response.hasProfileImage()).isTrue();
    }

    /**
     * AUTH-AC-18 — 스트리밍은 오브젝트 바이트와 저장된 contentType 을 함께 준다.
     *
     * <p>전에는 presigned GET URL 을 내려보냈다. 그 URL 의 호스트는 서버가 MinIO 에 붙는
     * {@code S3_ENDPOINT} 에서 나오므로 브라우저가 열 수 없었다 — 프로덕션에서 프로필
     * 이미지가 깨져 있던 원인이다.
     */
    @Test
    void loadMyProfileImageReturnsTheObjectBytesAndItsContentType() {
        when(memberRepository.findByEmail(LOGIN_ID))
                .thenReturn(Optional.of(member(42L, "profiles/42/uuid/avatar.png")));
        when(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("woobeee")
                .key("profiles/42/uuid/avatar.png")
                .build()))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType("image/png").build(),
                        new byte[]{1, 2, 3}
                ));

        MemberProfileImageService.ProfileImage image = memberProfileImageService.loadMyProfileImage(LOGIN_ID);

        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.contentType()).isEqualTo("image/png");
    }

    /** AUTH-AC-19 — 프로필 이미지를 설정하지 않았으면 404 다. */
    @Test
    void loadMyProfileImageIsNotFoundWhenTheMemberHasNoImage() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        assertThatThrownBy(() -> memberProfileImageService.loadMyProfileImage(LOGIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(s3Client);
    }

    /**
     * AUTH-AC-19 — 컬럼은 있는데 오브젝트가 없으면 500 이 아니라 404 다. 삭제가 반쯤 실패한
     * 상태(컬럼은 남고 오브젝트만 사라진 경우)에서 화면이 오류로 무너지지 않아야 한다.
     */
    @Test
    void aMissingObjectBecomesNotFoundRatherThanAServerError() {
        when(memberRepository.findByEmail(LOGIN_ID))
                .thenReturn(Optional.of(member(42L, "profiles/42/uuid/gone.png")));
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("nope").build());

        assertThatThrownBy(() -> memberProfileImageService.loadMyProfileImage(LOGIN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
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

    /**
     * AUTH-AC-14 — {@code GET /me} 는 이미지 <b>존재 여부</b>를 반환한다.
     *
     * <p>URL 이 아니라 boolean 인 것이 요점이다. 프론트는 인증 헤더를 붙여 따로 받아야 하므로
     * (`<img>` 는 Authorization 을 못 보낸다) 여기서 URL 을 주면 쓸 수 없는 값이 된다.
     */
    @Test
    void getMyProfileReportsWhetherAProfileImageExists() {
        when(memberRepository.findByEmail(LOGIN_ID))
                .thenReturn(Optional.of(member(42L, "profiles/42/uuid/avatar.png")));

        MemberProfileResponse response = memberProfileImageService.getMyProfile(LOGIN_ID);

        assertThat(response.memberId()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo(LOGIN_ID);
        assertThat(response.nickname()).isEqualTo("nick");
        assertThat(response.gameMoney()).isZero();
        assertThat(response.hasProfileImage()).isTrue();
        verifyNoInteractions(s3Client);
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReportsNoImageWhenUnset() {
        when(memberRepository.findByEmail(LOGIN_ID)).thenReturn(Optional.of(member(42L, null)));

        MemberProfileResponse response = memberProfileImageService.getMyProfile(LOGIN_ID);

        assertThat(response.hasProfileImage()).isFalse();
        verifyNoInteractions(s3Client);
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
