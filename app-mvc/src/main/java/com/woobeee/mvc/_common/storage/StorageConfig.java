package com.woobeee.mvc._common.storage;

import java.net.URI;
import java.time.Clock;

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
     * presigned URL 의 서명 시각을 정하는 클록. 빈으로 두는 이유는 테스트가 고정하기 위함이다 --
     * {@link PresignedUrlFactory} 는 시각을 시간 단위로 내려 URL 을 결정적으로 만들고, 그 성질을
     * 검증하려면 시각을 마음대로 옮길 수 있어야 한다.
     */
    @Bean
    public Clock storageClock() {
        return Clock.systemUTC();
    }

    private StaticCredentialsProvider credentialsProvider(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
        );
    }
}
