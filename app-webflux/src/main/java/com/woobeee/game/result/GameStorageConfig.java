package com.woobeee.game.result;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * app-mvc 의 StorageConfig 와 짝을 이루지만 <b>비동기 클라이언트</b>를 만든다.
 * S3Client 는 블로킹이라 WebFlux 이벤트 루프를 막는다.
 */
@Configuration
@EnableConfigurationProperties(GameStorageProperties.class)
public class GameStorageConfig {

    @Bean
    public S3AsyncClient s3AsyncClient(GameStorageProperties properties) {
        S3AsyncClientBuilder builder = S3AsyncClient.builder()
                .credentialsProvider(credentials(properties))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccessEnabled())
                        .build());

        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }

    @Bean
    public S3Presigner gameS3Presigner(GameStorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentials(properties))
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccessEnabled())
                        .build());

        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }

        return builder.build();
    }

    private StaticCredentialsProvider credentials(GameStorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
        );
    }
}
