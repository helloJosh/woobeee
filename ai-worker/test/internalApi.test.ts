import { describe, expect, it, vi } from "vitest";
import { createInternalApi } from "../src/internalApi.js";

const ok = (data: unknown) => new Response(JSON.stringify({ header: { isSuccessful: true, resultCode: 200 }, data }), { status: 200 });
const status = (code: number) => new Response(JSON.stringify({ header: { isSuccessful: false, resultCode: code } }), { status: code });

function api(fetchFn: typeof fetch) {
  return createInternalApi("http://app-mvc:8000/", "s3cret", fetchFn);
}

describe("internal api client", () => {
  it("AI_AC_13 sends the worker secret on every call and builds the url from the base", async () => {
    const fetchFn = vi.fn(async () => ok({ id: "r1", attempt: 1, text: "x", imageKeys: [] }));

    await api(fetchFn as unknown as typeof fetch).claim("r1");

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://app-mvc:8000/internal/ai/requests/r1/claim");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["X-Ai-Worker-Secret"]).toBe("s3cret");
  });

  it("AI_AC_05 turns a 200 claim into the work to do", async () => {
    const fetchFn = vi.fn(async () => ok({ id: "r1", attempt: 3, text: "GO패스", imageKeys: ["ai/r1/0"] }));

    await expect(api(fetchFn as unknown as typeof fetch).claim("r1")).resolves.toEqual({
      kind: "claimed", attempt: 3, text: "GO패스", imageKeys: ["ai/r1/0"],
    });
  });

  it("AI_AC_05 turns a 409 claim into a skip", async () => {
    const fetchFn = vi.fn(async () => status(409));

    await expect(api(fetchFn as unknown as typeof fetch).claim("r1")).resolves.toEqual({ kind: "skip" });
  });

  it("AI_AC_16 throws on 401, 5xx and network errors so the event is retried rather than lost", async () => {
    for (const make of [() => status(401), () => status(500), () => status(503)]) {
      const fetchFn = vi.fn(async () => make());
      await expect(api(fetchFn as unknown as typeof fetch).claim("r1")).rejects.toThrow();
    }
    const down = vi.fn(async () => { throw new TypeError("fetch failed"); });
    await expect(api(down as unknown as typeof fetch).claim("r1")).rejects.toThrow("fetch failed");
  });

  it("posts complete and fail with the attempt and treats a 409 as already settled", async () => {
    const fetchFn = vi.fn(async () => ok(null));
    const client = api(fetchFn as unknown as typeof fetch);

    await client.complete("r1", 2, "요약");
    await client.fail("r1", 2, "timeout");

    const bodies = fetchFn.mock.calls.map((c) => JSON.parse(((c as unknown as [string, RequestInit])[1].body as string)));
    expect(bodies).toEqual([{ attempt: 2, summary: "요약" }, { attempt: 2, error: "timeout" }]);

    const conflict = vi.fn(async () => status(409));
    await expect(api(conflict as unknown as typeof fetch).complete("r1", 1, "낡은 보고")).resolves.toBeUndefined();
    await expect(api(conflict as unknown as typeof fetch).fail("r1", 1, "x")).resolves.toBeUndefined();
  });

  it("throws when a complete report cannot be delivered", async () => {
    const fetchFn = vi.fn(async () => status(500));

    await expect(api(fetchFn as unknown as typeof fetch).complete("r1", 1, "x")).rejects.toThrow();
  });
});
