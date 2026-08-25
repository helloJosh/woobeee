package com.woobeee.mvc._common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "storage.s3")
public class StorageProperties {
    /** 서버가 MinIO 에 붙는 주소. 컨테이너 내부 주소여도 된다 -- 브라우저는 이 값을 보지 않는다. */
    private String endpoint;

    /**
     * <b>브라우저</b>가 여는 MinIO 주소. presigned URL 의 host 가 이 값에서 나온다.
     *
     * <p>{@link #endpoint} 와 반드시 분리해야 한다. 하나로 쓰면 서명은 유효한데 host 가
     * localhost 나 컨테이너 내부 주소라 외부 방문자가 못 여는 URL 이 만들어진다 -- 서명이
     * host 를 포함하므로 나중에 문자열로 갈아끼울 수도 없다.
     *
     * <p>비어 있으면 {@link #endpoint} 로 폴백한다(로컬 개발).
     */
    private String publicEndpoint;
    private String region;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private long presignedUrlExpirationSeconds = 600;
    private boolean pathStyleAccessEnabled = true;
}
