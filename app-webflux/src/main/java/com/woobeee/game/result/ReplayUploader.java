package com.woobeee.game.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 기보를 MinIO 에 올린다.
 *
 * <p>업로드 실패를 에러로 전파하지 않고 빈 Mono 로 끝낸다. 결과 행은 이미 커밋됐고, 여기서
 * 실패해도 전적은 남아야 한다. 잃는 것은 다시보기뿐이다.
 */
@Component
public class ReplayUploader {
    private static final Logger log = LoggerFactory.getLogger(ReplayUploader.class);
    private static final String CONTENT_TYPE = "application/x-ndjson";

    private final S3AsyncClient s3AsyncClient;
    private final S3Presigner s3Presigner;
    private final GameStorageProperties properties;

    public ReplayUploader(
            S3AsyncClient s3AsyncClient,
            S3Presigner gameS3Presigner,
            GameStorageProperties properties
    ) {
        this.s3AsyncClient = s3AsyncClient;
        this.s3Presigner = gameS3Presigner;
        this.properties = properties;
    }

    public Mono<String> upload(String gameType, long gameResultId, String ndjson) {
        String key = objectKey(gameType, gameResultId);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(CONTENT_TYPE)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.putObject(
                        request,
                        AsyncRequestBody.fromBytes(ndjson.getBytes(StandardCharsets.UTF_8))))
                .thenReturn(key)
                .onErrorResume(error -> {
                    log.warn("Failed to upload replay. key={}", key, error);
                    return Mono.empty();
                });
    }

    public String presignedDownloadUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getPresignedUrlExpirationSeconds()))
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    static String objectKey(String gameType, long gameResultId) {
        return "games/" + gameType + "/" + gameResultId + ".ndjson";
    }
}
