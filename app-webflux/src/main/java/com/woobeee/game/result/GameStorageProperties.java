package com.woobeee.game.result;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage.s3")
public class GameStorageProperties {
    private String endpoint;
    private String region = "ap-northeast-2";
    private String bucket;
    private String accessKey;
    private String secretKey;
    private long presignedUrlExpirationSeconds = 600;
    private boolean pathStyleAccessEnabled = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public long getPresignedUrlExpirationSeconds() {
        return presignedUrlExpirationSeconds;
    }

    public void setPresignedUrlExpirationSeconds(long presignedUrlExpirationSeconds) {
        this.presignedUrlExpirationSeconds = presignedUrlExpirationSeconds;
    }

    public boolean isPathStyleAccessEnabled() {
        return pathStyleAccessEnabled;
    }

    public void setPathStyleAccessEnabled(boolean pathStyleAccessEnabled) {
        this.pathStyleAccessEnabled = pathStyleAccessEnabled;
    }
}
