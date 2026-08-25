package com.woobeee.mvc.auth.service;

import com.woobeee.mvc._common.storage.StorageProperties;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 프로필 이미지의 업로드 / 조회 / 삭제를 담당한다.
 *
 * <p>업로드와 조회 <b>둘 다 앱을 거친다</b>. 전에는 presigned PUT/GET 으로 브라우저가 MinIO 에
 * 직접 붙었는데, presigned URL 의 호스트는 서버가 MinIO 에 붙는 {@code S3_ENDPOINT} 에서 나온다
 * — 브라우저가 열 주소가 아니라서 프로덕션에서는 업로드도 표시도 되지 않았다. 앱을 거치면
 * 버킷을 공개하지 않고도 열리고, 만료도 없다. 같은 이유로 글 본문 이미지도 앱이 스트리밍한다.
 *
 * <p>키의 memberId 는 요청이 아니라 토큰에서 온 회원으로 정한다 — 요청 값을 믿으면 남의
 * 프로필에 오브젝트를 붙일 수 있다.
 *
 * <p>업로드·삭제는 트랜잭션을 열지 않는다. {@code memberRepository.save} 가 단건 커밋한 뒤에
 * 이전 오브젝트를 지워야, 삭제가 실패해도 프로필이 정상으로 남고 고아 오브젝트만 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberProfileImageService {
    static final String PROFILE_PREFIX = "profiles/";

    /** 업로드가 앱을 거치므로 상한이 없으면 큰 파일이 그대로 앱 힙을 받는다. */
    static final long MAX_PROFILE_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif"
    );

    private final MemberRepository memberRepository;
    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    /** 프로필 이미지를 올리거나 교체한다. 이전 오브젝트는 저장이 커밋된 뒤에 지운다. */
    public MemberProfileResponse upload(String loginId, MultipartFile file) {
        Member member = requireMember(loginId);
        String contentType = normalizeContentType(file == null ? null : file.getContentType());
        requireAcceptableSize(file);

        // 키의 memberId 는 요청 본문이 아니라 토큰에서 온 회원으로 정한다.
        String fileKey = keyPrefixOf(member.getId())
                + UUID.randomUUID() + "/" + sanitizeFileName(file.getOriginalFilename());

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.getBucket())
                            .key(fileKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store the profile image"
            );
        }

        String previousFileKey = member.getProfileImageKey();
        member.changeProfileImageKey(fileKey);
        memberRepository.save(member);

        if (StringUtils.hasText(previousFileKey) && !previousFileKey.equals(fileKey)) {
            deleteQuietly(previousFileKey);
        }

        return profileOf(member);
    }

    /**
     * 프로필 이미지 바이트를 읽어 온다. 버킷은 비공개로 두고 앱이 자격증명으로 대신 읽는다.
     *
     * <p><b>인증하지 않는다.</b> 아바타는 남이 봐야 하는 이미지다 -- 댓글 작성자 아바타를
     * 그리려면 남의 것을 받을 수 있어야 하고, {@code <img>} 는 Authorization 헤더를 못 보낸다.
     * 오브젝트 키에 UUID 가 들어 있어 키 자체는 열거되지 않지만, 이 엔드포인트는 회원 id 로
     * 조회하므로 <b>id 를 아는 누구나 볼 수 있다</b>. 그것이 의도다.
     *
     * <p>컬럼이 비었거나 오브젝트가 없으면 404 다. 후자는 삭제가 반쯤 실패해 컬럼만 남은
     * 상태에서 화면이 500 으로 무너지지 않게 하려는 것이다.
     */
    public ProfileImage loadProfileImage(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member is not found"));
        String fileKey = member.getProfileImageKey();

        if (!StringUtils.hasText(fileKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile image is not set");
        }

        try {
            ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(storageProperties.getBucket())
                            .key(fileKey)
                            .build()
            );

            return new ProfileImage(object.asByteArray(), object.response().contentType(), eTagOf(fileKey));
        } catch (NoSuchKeyException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile image object is missing");
        }
    }

    public void delete(String loginId) {
        Member member = requireMember(loginId);

        String fileKey = member.getProfileImageKey();
        if (!StringUtils.hasText(fileKey)) {
            return;
        }

        member.removeProfileImageKey();
        memberRepository.save(member);
        deleteQuietly(fileKey);
    }

    public MemberProfileResponse getMyProfile(String loginId) {
        return profileOf(requireMember(loginId));
    }

    /**
     * 스트리밍할 오브젝트 한 개. contentType 은 업로드 때 저장된 값이다.
     *
     * @param eTag 오브젝트 키에서 파생한 버전. 키에 UUID 가 있어 이미지를 교체하면 반드시
     *             바뀌므로, 브라우저가 {@code If-None-Match} 로 재검증할 수 있다.
     */
    public record ProfileImage(byte[] bytes, String contentType, String eTag) {
    }

    /** 키를 그대로 노출하지 않으려고 해시로 줄인다. 키가 바뀌면 값도 바뀐다. */
    private static String eTagOf(String fileKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fileKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private MemberProfileResponse profileOf(Member member) {
        return new MemberProfileResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getGameMoney(),
                profileImageUrlOf(member)
        );
    }

    /**
     * 프로필 이미지 주소. 미설정이면 {@code null} 이다.
     *
     * <p>같은 오리진의 상대 경로다 -- 프론트가 {@code /api/auth/*} 를 이미 프록시하므로 도메인
     * 설정이 필요 없고, presigned URL 과 달리 조회마다 값이 바뀌지 않아 캐시가 걸린다.
     */
    public static String profileImageUrlOf(Member member) {
        if (!StringUtils.hasText(member.getProfileImageKey())) {
            return null;
        }
        return "/api/auth/members/" + member.getId() + "/profile-image";
    }

    private Member requireMember(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }

        return memberRepository.findByEmail(loginId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required"));
    }

    private String keyPrefixOf(Long memberId) {
        return PROFILE_PREFIX + memberId + "/";
    }

    private String normalizeContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED_CONTENT_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported profile image content type");
        }
        return normalized;
    }

    /** 빈 파일도 거절한다 — 0바이트 오브젝트를 프로필로 붙이면 조회가 깨진 이미지가 된다. */
    private void requireAcceptableSize(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile image file is empty");
        }
        if (file.getSize() > MAX_PROFILE_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile image must be 5MB or smaller");
        }
    }

    private void deleteQuietly(String fileKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(fileKey)
                    .build());
        } catch (RuntimeException exception) {
            log.warn("Failed to delete profile image object. key={}", fileKey, exception);
        }
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName == null ? "" : fileName.trim().replace("\\", "/");
        int lastSlashIndex = sanitized.lastIndexOf('/');
        if (lastSlashIndex >= 0) {
            sanitized = sanitized.substring(lastSlashIndex + 1);
        }

        sanitized = sanitized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!StringUtils.hasText(sanitized)) {
            return "image";
        }
        return sanitized;
    }
}
