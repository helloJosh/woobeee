import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { createImageFetcher, extensionFor } from "../src/s3Images.js";

describe("extensionFor", () => {
  it("maps the stored content type to a file extension Claude's Read tool recognises", () => {
    expect(extensionFor("image/png")).toBe(".png");
    expect(extensionFor("image/jpeg")).toBe(".jpg");
    expect(extensionFor("image/webp")).toBe(".webp");
    expect(extensionFor(undefined)).toBe(".png");
  });
});

describe("createImageFetcher", () => {
  it("downloads each key into the temp dir with an extension and returns the paths in order", async () => {
    const dir = await mkdtemp(join(tmpdir(), "ai-worker-test-"));
    try {
      const send = vi.fn(async (command: { input: { Key: string } }) => ({
        ContentType: command.input.Key.endsWith("/1") ? "image/jpeg" : "image/png",
        Body: { transformToByteArray: async () => new TextEncoder().encode(`bytes of ${command.input.Key}`) },
      }));
      const fetchImages = createImageFetcher({ send } as never, "woobeee");

      const paths = await fetchImages(["ai/r1/0", "ai/r1/1"], dir);

      expect(paths).toEqual([join(dir, "image-0.png"), join(dir, "image-1.jpg")]);
      expect(await readFile(paths[0]!, "utf8")).toBe("bytes of ai/r1/0");
      expect(send.mock.calls[0]![0].input).toMatchObject({ Bucket: "woobeee", Key: "ai/r1/0" });
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("propagates a missing object so the handler can report it", async () => {
    const send = vi.fn(async () => { throw new Error("NoSuchKey"); });
    const fetchImages = createImageFetcher({ send } as never, "woobeee");

    await expect(fetchImages(["ai/r1/0"], "/tmp/none")).rejects.toThrow("NoSuchKey");
  });
});
