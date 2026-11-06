package com.woobeee.mvc.ai.service;

import com.woobeee.mvc._common.storage.PresignedUrlFactory;
import com.woobeee.mvc._common.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** AI 요청 이미지를 MinIO 에 두고 지우고 주소를 만든다. 서비스가 S3 SDK 를 직접 쥐지 않게 하는 이음매다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiImageStore {
    private final S3Client s3Client;
    private final StorageProperties storage;
    private final PresignedUrlFactory presignedUrls;

    public void put(String key, byte[] bytes, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(storage.getBucket()).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
    }

    /** 정리용이라 실패해도 삼킨다 — 지우기가 접수 실패의 원인을 가리면 안 된다. */
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(storage.getBucket()).key(key).build());
        } catch (RuntimeException ex) {
            log.warn("could not delete the ai image {}: {}", key, ex.toString());
        }
    }

    /** 브라우저가 여는 presigned 주소. */
    public String url(String key) {
        return presignedUrls.getUrl(key);
    }
}
