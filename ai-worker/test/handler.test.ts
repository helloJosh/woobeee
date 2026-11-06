import { describe, expect, it, vi } from "vitest";
import { handleEvent, type HandlerDeps } from "../src/handler.js";
import type { ClaimResult, InternalApi } from "../src/internalApi.js";

const EVENT = { requestId: "req-1" };

function fakeApi(claim: ClaimResult | Error): InternalApi {
  return {
    claim: vi.fn(async () => {
      if (claim instanceof Error) throw claim;
      return claim;
    }),
    complete: vi.fn(async () => {}),
    fail: vi.fn(async () => {}),
  };
}

function deps(api: InternalApi, over: Partial<HandlerDeps> = {}): HandlerDeps {
  return {
    api,
    mkTmp: vi.fn(async () => "/tmp/work-1"),
    rm: vi.fn(async () => {}),
    fetchImages: vi.fn(async () => ["/tmp/work-1/image-0.png"]),
    run: vi.fn(async () => ({ ok: true as const, summary: "상품 2개를 찾았습니다" })),
    ...over,
  };
}

const CLAIMED: ClaimResult = { kind: "claimed", attempt: 2, text: "GO패스 추가", imageKeys: ["ai/req-1/0"] };

describe("handleEvent", () => {
  it("AI_AC_16 skips without any work when the claim conflicts", async () => {
    const api = fakeApi({ kind: "skip" });
    const d = deps(api);

    await expect(handleEvent(d, EVENT)).resolves.toBe("skipped");

    expect(d.run).not.toHaveBeenCalled();
    expect(d.mkTmp).not.toHaveBeenCalled();
    expect(api.complete).not.toHaveBeenCalled();
    expect(api.fail).not.toHaveBeenCalled();
  });

  it("AI_AC_16 completes with the summary and passes the claim's attempt and inputs to the run", async () => {
    const api = fakeApi(CLAIMED);
    const d = deps(api);

    await expect(handleEvent(d, EVENT)).resolves.toBe("done");

    expect(api.complete).toHaveBeenCalledWith("req-1", 2, "상품 2개를 찾았습니다");
    expect(d.run).toHaveBeenCalledWith({
      requestId: "req-1",
      attempt: 2,
      text: "GO패스 추가",
      imagePaths: ["/tmp/work-1/image-0.png"],
      dir: "/tmp/work-1",
    });
  });

  it("AI_AC_16 reports a failed run through api.fail with the run's error", async () => {
    const api = fakeApi(CLAIMED);
    const d = deps(api, { run: vi.fn(async () => ({ ok: false as const, error: "timeout" })) });

    await expect(handleEvent(d, EVENT)).resolves.toBe("failed");

    expect(api.fail).toHaveBeenCalledWith("req-1", 2, "timeout");
    expect(api.complete).not.toHaveBeenCalled();
  });

  it("AI_AC_16 reports a run that throws instead of letting it escape", async () => {
    const api = fakeApi(CLAIMED);
    const d = deps(api, { run: vi.fn(async () => { throw new Error("spawn ENOENT"); }) });

    await expect(handleEvent(d, EVENT)).resolves.toBe("failed");

    expect(api.fail).toHaveBeenCalledWith("req-1", 2, expect.stringContaining("spawn ENOENT"));
  });

  it("AI_AC_16 reports an image download failure without running claude", async () => {
    const api = fakeApi(CLAIMED);
    const d = deps(api, { fetchImages: vi.fn(async () => { throw new Error("NoSuchKey"); }) });

    await expect(handleEvent(d, EVENT)).resolves.toBe("failed");

    expect(d.run).not.toHaveBeenCalled();
    expect(api.fail).toHaveBeenCalledWith("req-1", 2, expect.stringContaining("NoSuchKey"));
  });

  it("AI_AC_16 always removes the temp dir — success, failure and a throwing report", async () => {
    for (const make of [
      () => deps(fakeApi(CLAIMED)),
      () => deps(fakeApi(CLAIMED), { run: vi.fn(async () => ({ ok: false as const, error: "x" })) }),
      () => deps(fakeApi(CLAIMED), { run: vi.fn(async () => { throw new Error("boom"); }) }),
      () => {
        const api = fakeApi(CLAIMED);
        api.complete = vi.fn(async () => { throw new Error("app-mvc down"); });
        return deps(api);
      },
    ]) {
      const d = make();
      await handleEvent(d, EVENT).catch(() => {});
      expect(d.rm).toHaveBeenCalledTimes(1);
      expect(d.rm).toHaveBeenCalledWith("/tmp/work-1");
    }
  });

  it("AI_AC_16 throws when app-mvc cannot be reached, so the offset is not committed", async () => {
    const api = fakeApi(new Error("connect ECONNREFUSED"));
    const d = deps(api);

    await expect(handleEvent(d, EVENT)).rejects.toThrow("ECONNREFUSED");

    expect(d.run).not.toHaveBeenCalled();
  });

  it("AI_AC_16 throws when the final report cannot be delivered", async () => {
    const api = fakeApi(CLAIMED);
    api.complete = vi.fn(async () => { throw new Error("app-mvc down"); });

    await expect(handleEvent(deps(api), EVENT)).rejects.toThrow("app-mvc down");
  });
});
