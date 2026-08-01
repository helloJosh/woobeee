package com.woobeee.game.result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayUploaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private S3AsyncClient s3AsyncClient;
    private S3Presigner s3Presigner;
    private ReplayUploader uploader;

    @BeforeEach
    void setUp() {
        s3AsyncClient = mock(S3AsyncClient.class);
        s3Presigner = mock(S3Presigner.class);

        GameStorageProperties properties = new GameStorageProperties();
        properties.setBucket("woobeee");
        properties.setPresignedUrlExpirationSeconds(600);

        uploader = new ReplayUploader(s3AsyncClient, s3Presigner, properties);
    }

    @Test
    void uploadPutsNdjsonUnderTheGameTypeAndIdAndReturnsTheKey() {
        when(s3AsyncClient.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));

        StepVerifier.create(uploader.upload("OMOK", 77L, "{\"v\":1}\n"))
                .expectNext("games/OMOK/77.ndjson")
                .expectComplete()
                .verify(TIMEOUT);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3AsyncClient).putObject(captor.capture(), any(AsyncRequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("woobeee");
        assertThat(captor.getValue().key()).isEqualTo("games/OMOK/77.ndjson");
        assertThat(captor.getValue().contentType()).isEqualTo("application/x-ndjson");
    }

    /** GAME-AC-21 — 업로드 실패는 결과를 무르지 않고 빈 Mono 로 끝난다 */
    @Test
    void uploadFailureCompletesEmptyInsteadOfErroring() {
        when(s3AsyncClient.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage down")));

        StepVerifier.create(uploader.upload("OMOK", 77L, "{}"))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void presignedDownloadUrlUsesTheConfiguredTtl() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://s3.example.com/get").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        assertThat(uploader.presignedDownloadUrl("games/OMOK/77.ndjson"))
                .isEqualTo("https://s3.example.com/get");

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().signatureDuration()).isEqualTo(java.time.Duration.ofSeconds(600));
    }

    @Test
    void presignedDownloadUrlIsNullForABlankKey() {
        assertThat(uploader.presignedDownloadUrl(null)).isNull();
        assertThat(uploader.presignedDownloadUrl(" ")).isNull();
    }
}
