package com.woobeee.mvc.auth.service;

import com.woobeee.mvc._common.storage.StorageProperties;
import com.woobeee.mvc.auth.api.request.MemberProfileImagePresignedUrlRequest;
import com.woobeee.mvc.auth.api.request.MemberProfileImageRegisterRequest;
import com.woobeee.mvc.auth.api.response.MemberProfileImageResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileImageUploadUrlResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 프로필 이미지의 presigned PUT 업로드 / presigned GET 조회를 담당한다.
 *
 * <p>파일 바이트가 서버를 경유하지 않으므로 contentType 화이트리스트와 fileKey prefix 검증으로
 * 임의 오브젝트가 남의 프로필에 붙는 것을 막는다.
 *
 * <p>등록·삭제는 트랜잭션을 열지 않는다. {@code memberRepository.save} 가 단건 커밋한 뒤에
 * 이전 오브젝트를 지워야, 삭제가 실패해도 프로필이 정상으로 남고 고아 오브젝트만 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberProfileImageService {
    static final String PROFILE_PREFIX = "profiles/";

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif"
    );

    private final MemberRepository memberRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    public MemberProfileImageUploadUrlResponse createPresignedUploadUrl(
            String loginId,
            MemberProfileImagePresignedUrlRequest request
    ) {
        Member member = requireMember(loginId);
        String contentType = normalizeContentType(request.contentType());

        // 키의 memberId 는 요청 본문이 아니라 토큰에서 온 회원으로 정한다.
        String fileKey = keyPrefixOf(member.getId()) + UUID.randomUUID() + "/" + sanitizeFileName(request.fileName());

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(fileKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(storageProperties.getPresignedUrlExpirationSeconds()))
                .putObjectRequest(putObjectRequest)
                .build();

        return new MemberProfileImageUploadUrlResponse(
                s3Presigner.presignPutObject(presignRequest).url().toString(),
                fileKey,
                storageProperties.getPresignedUrlExpirationSeconds()
        );
    }

    public MemberProfileImageResponse register(String loginId, MemberProfileImageRegisterRequest request) {
        Member member = requireMember(loginId);
        String fileKey = request.fileKey() == null ? null : request.fileKey().trim();

        if (!StringUtils.hasText(fileKey) || !fileKey.startsWith(keyPrefixOf(member.getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File key does not belong to the requester");
        }

        String previousFileKey = member.getProfileImageKey();
        member.changeProfileImageKey(fileKey);
        memberRepository.save(member);

        if (StringUtils.hasText(previousFileKey) && !previousFileKey.equals(fileKey)) {
            deleteQuietly(previousFileKey);
        }

        return new MemberProfileImageResponse(createPresignedDownloadUrl(fileKey));
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
        Member member = requireMember(loginId);

        return new MemberProfileResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getGameMoney(),
                createPresignedDownloadUrl(member.getProfileImageKey())
        );
    }

    private String createPresignedDownloadUrl(String fileKey) {
        if (!StringUtils.hasText(fileKey)) {
            return null;
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(fileKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(storageProperties.getPresignedUrlExpirationSeconds()))
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
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
