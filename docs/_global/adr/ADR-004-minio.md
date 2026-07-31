# ADR-004. 파일 저장소로 S3 호환 스토리지(MinIO/S3)와 Presigned URL을 사용한다

- 상태: 적용
- 범위: 전역(Global)

## 맥락

상품 이미지 등 대용량 파일을 애플리케이션 서버가 직접 중계하면 메모리·대역폭 부담이 크다. 로컬과 운영에서 동일한 인터페이스로 다룰 수 있어야 한다.

## 결정

- **S3 호환 스토리지**를 파일 저장소로 사용하고, AWS SDK v2(`software.amazon.awssdk:s3`)로 접근한다.
- 로컬은 **MinIO**를 S3 호환 엔드포인트로 사용한다 (`.docker-compose`의 `minio` + `createbuckets` 서비스가 버킷을 초기화).
- 설정은 `StorageProperties`/`StorageConfig`가 `application.yaml`의 `storage.s3`(endpoint, region, bucket, access-key, secret-key, presigned-url-expiration-seconds, path-style-access-enabled)에서 바인딩한다.
- 업로드는 **Presigned URL 기반 클라이언트 직접 업로드**를 우선한다.
  - 업로드용 Presigned PUT URL과 브라우저 표시용 Presigned GET URL을 모두 `ProductImageStorageService`가 생성한다.
  - `path-style-access-enabled: true`로 MinIO 경로 스타일 접근을 허용한다.
- 객체 key는 DB에 보관하고, 응답에는 만료형 Presigned download URL을 함께 내려준다.

## 영향

- 로컬 검증 시 MinIO 컨테이너와 버킷(기본 `woobeee`)이 필요하다.
- presigned URL은 만료 시간(기본 600초)을 가지므로 클라이언트는 만료 전에 사용해야 한다.

## 관련

- 상품 이미지 업로드/이동/복구 상세: [`product/adr/ADR-001-imageupload.md`](../../product/adr/ADR-001-imageupload.md).
