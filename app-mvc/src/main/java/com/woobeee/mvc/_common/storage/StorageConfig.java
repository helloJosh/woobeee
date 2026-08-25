package com.woobeee.mvc._common.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
    @Bean
    public S3Configuration s3Configuration(StorageProperties properties) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyleAccessEnabled())
                .build();
    }

    @Bean
    public S3Client s3Client(StorageProperties properties, S3Configuration s3Configuration) {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(credentialsProvider(properties))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(s3Configuration);

        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }

    /**
     * presigned URL 전용 presigner. <b>공개</b> endpoint 로 서명한다.
     *
     * <p>presigner 가 존재하는 이유는 "남이 열 URL" 을 만드는 것이므로, 서버가 MinIO 에 붙는
     * {@code endpoint} 가 아니라 브라우저가 닿는 {@code publicEndpoint} 를 써야 한다. 서명은
     * host 를 포함하기 때문에 나중에 문자열 치환으로 고칠 수 없다 -- 여기서 정해야 한다.
     * 서버 대 서버 작업은 {@link #s3Client} 가 내부 endpoint 로 처리한다.
     */
    @Bean
    public S3Presigner s3Presigner(StorageProperties properties, S3Configuration s3Configuration) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider(properties))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(s3Configuration);

        String browserFacing = StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint()
                : properties.getEndpoint();

        if (StringUtils.hasText(browserFacing)) {
            builder.endpointOverride(URI.create(browserFacing));
        }

        return builder.build();
    }

    private StaticCredentialsProvider credentialsProvider(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
        );
    }
}
