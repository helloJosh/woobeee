package com.woobeee.mvc._common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "storage.s3")
public class StorageProperties {
    private String endpoint;

    /**
     * 브라우저가 이미지를 여는 base URL. 서버가 MinIO 에 붙는 {@link #endpoint} 와 다르다 --
     * 컨테이너 내부 주소나 localhost 를 본문에 박으면 외부 방문자는 열 수 없다.
     * 프로덕션은 공개 도메인을, 로컬은 기본값(MinIO 직결)을 쓴다.
     */
    private String publicBaseUrl;
    private String region;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private long presignedUrlExpirationSeconds = 600;
    private boolean pathStyleAccessEnabled = true;
}
