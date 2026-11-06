import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { GetObjectCommand, type S3Client } from "@aws-sdk/client-s3";

/**
 * 오브젝트 키(`ai/{id}/{n}`)에는 확장자가 없다. 저장해 둔 콘텐츠 타입에서 확장자를 정해 두면 claudeRunner 가
 * 그 확장자로 base64 블록의 media_type 을 정한다.
 */
export function extensionFor(contentType: string | undefined): string {
  switch (contentType?.toLowerCase()) {
    case "image/jpeg":
      return ".jpg";
    case "image/webp":
      return ".webp";
    default:
      return ".png";
  }
}

/** 키 목록을 임시 폴더로 내려받아 파일 경로를 같은 순서로 돌려준다. 없는 오브젝트는 던진다 — 핸들러가 보고한다. */
export function createImageFetcher(client: Pick<S3Client, "send">, bucket: string) {
  return async function fetchImages(keys: string[], dir: string): Promise<string[]> {
    const paths: string[] = [];
    for (const [index, key] of keys.entries()) {
      const object = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
      const bytes = await object.Body!.transformToByteArray();
      const path = join(dir, `image-${index}${extensionFor(object.ContentType)}`);
      await writeFile(path, bytes);
      paths.push(path);
    }
    return paths;
  };
}
