import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import { describe, expect, it, vi } from "vitest";
import { buildClaudeEnv, runClaude, type SpawnFn } from "../src/claudeRunner.js";

type FakeChild = EventEmitter & {
  stdin: PassThrough;
  stdout: PassThrough;
  stderr: PassThrough;
  kill: ReturnType<typeof vi.fn>;
  written: () => string;
};

function fakeChild(): FakeChild {
  const child = new EventEmitter() as FakeChild;
  child.stdin = new PassThrough();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.kill = vi.fn();
  let written = "";
  child.stdin.on("data", (chunk: Buffer) => { written += chunk.toString(); });
  child.written = () => written;
  return child;
}

function spawnReturning(child: FakeChild): SpawnFn & ReturnType<typeof vi.fn> {
  return vi.fn(() => child) as unknown as SpawnFn & ReturnType<typeof vi.fn>;
}

const INPUT = {
  prompt: "이미지를 보고 제안해줘",
  imagePaths: ["/tmp/w/image-0.png", "/tmp/w/image-1.jpg"],
  mcpConfigPath: "/tmp/w/mcp.json",
  cwd: "/tmp/w",
  timeoutMs: 1000,
  maxTurns: 12,
};
const ENV = { PATH: "/usr/bin", HOME: "/home/worker", CLAUDE_CODE_OAUTH_TOKEN: "tok", ANTHROPIC_API_KEY: "key", AI_WORKER_SECRET: "s3cret", KAFKA_BOOTSTRAP_SERVERS: "k:1" };
const readFile = vi.fn(async (path: string) => Buffer.from(`bytes:${path}`));
const DEPS = { env: ENV, readFile: readFile as never };

/** claude --output-format stream-json 이 내는 줄들: 시작 알림, 어시스턴트 메시지, 마지막에 결과. */
const stream = (result: Record<string, unknown>) =>
  [
    JSON.stringify({ type: "system", subtype: "init" }),
    JSON.stringify({ type: "assistant", message: { content: [{ type: "text", text: "..." }] } }),
    JSON.stringify({ type: "result", ...result }),
  ].join("\n") + "\n";

async function finish(child: FakeChild, stdout: string, code = 0) {
  // runClaude 가 이미지를 읽은 뒤에야 spawn 하므로 한 틱 기다린다
  await new Promise((resolve) => setImmediate(resolve));
  child.stdout.end(stdout);
  child.emit("close", code);
}

describe("runClaude", () => {
  it("AI_AC_17 never allows the Read tool — the only allowed tool is the woobeee MCP server", async () => {
    const child = fakeChild();
    const spawn = spawnReturning(child);
    const done = runClaude(INPUT, { ...DEPS, spawn, bin: "claude" });
    await finish(child, stream({ is_error: false, result: "끝" }));
    await done;

    const [bin, args, options] = spawn.mock.calls[0] as [string, string[], { cwd: string }];
    expect(bin).toBe("claude");
    expect(args).toEqual([
      "-p",
      "--input-format", "stream-json",
      "--output-format", "stream-json",
      "--verbose",
      "--mcp-config", INPUT.mcpConfigPath,
      "--strict-mcp-config",
      "--allowedTools", "mcp__woobeee",
      "--max-turns", "12",
    ]);
    // 파일을 읽는 도구가 없으니 프롬프트 인젝션이 /proc/self/environ 같은 곳(OAuth 토큰·시크릿)을 열 방법이 없다
    expect(args.join(" ")).not.toMatch(/\bRead\b|\bBash\b|\bWrite\b|\bEdit\b/);
    expect(options.cwd).toBe("/tmp/w");
  });

  it("AI_AC_17 hands the images to claude over stdin as base64 image blocks, followed by the instructions", async () => {
    const child = fakeChild();
    const done = runClaude(INPUT, { ...DEPS, spawn: spawnReturning(child) });
    await finish(child, stream({ is_error: false, result: "끝" }));
    await done;

    const lines = child.written().trim().split("\n");
    expect(lines).toHaveLength(1);
    const message = JSON.parse(lines[0]!);
    expect(message.type).toBe("user");
    expect(message.message.role).toBe("user");
    expect(message.message.content).toEqual([
      { type: "image", source: { type: "base64", media_type: "image/png", data: Buffer.from("bytes:/tmp/w/image-0.png").toString("base64") } },
      { type: "image", source: { type: "base64", media_type: "image/jpeg", data: Buffer.from("bytes:/tmp/w/image-1.jpg").toString("base64") } },
      { type: "text", text: INPUT.prompt },
    ]);
    expect(child.stdin.writableEnded).toBe(true);
  });

  it("works for a text-only request (no image blocks)", async () => {
    const child = fakeChild();
    const done = runClaude({ ...INPUT, imagePaths: [] }, { ...DEPS, spawn: spawnReturning(child) });
    await finish(child, stream({ is_error: false, result: "끝" }));
    await done;

    expect(JSON.parse(child.written().trim()).message.content).toEqual([{ type: "text", text: INPUT.prompt }]);
  });

  it("reports an unreadable image as a failure without spawning claude", async () => {
    const spawn = spawnReturning(fakeChild());
    const failing = vi.fn(async () => { throw new Error("ENOENT: no such file"); });

    const result = await runClaude(INPUT, { env: ENV, spawn, readFile: failing as never });

    expect(result.ok).toBe(false);
    expect(result.ok ? "" : result.error).toContain("ENOENT");
    expect(spawn).not.toHaveBeenCalled();
  });

  it("AI_AC_17 never hands the worker secret or unrelated variables to the claude process", () => {
    const env = buildClaudeEnv(ENV);

    expect(env).not.toHaveProperty("AI_WORKER_SECRET");
    expect(env).not.toHaveProperty("KAFKA_BOOTSTRAP_SERVERS");
    expect(Object.keys(env).sort()).toEqual(["CLAUDE_CODE_OAUTH_TOKEN", "HOME", "PATH"]);
  });

  it("uses the subscription token when set and the API key only as a fallback", () => {
    expect(buildClaudeEnv(ENV)).not.toHaveProperty("ANTHROPIC_API_KEY");
    expect(buildClaudeEnv({ PATH: "/p", HOME: "/h", ANTHROPIC_API_KEY: "key" })).toMatchObject({ ANTHROPIC_API_KEY: "key" });
  });

  it("returns the final result event's text on success, ignoring the earlier stream events", async () => {
    const child = fakeChild();
    const done = runClaude(INPUT, { ...DEPS, spawn: spawnReturning(child) });
    await finish(child, stream({ subtype: "success", is_error: false, result: "상품 3개를 제안했습니다" }));

    await expect(done).resolves.toEqual({ ok: true, summary: "상품 3개를 제안했습니다" });
  });

  it("reports an error result, a non-zero exit and output without a result event as failures", async () => {
    const cases: Array<[string, number, string]> = [
      [stream({ is_error: true, result: "rate limited" }), 0, "rate limited"],
      [stream({ is_error: true, result: "authentication failed" }), 1, "authentication failed"],
      ["", 1, "exit code 1"],
      [`${JSON.stringify({ type: "system" })}\nnot json at all\n`, 0, "unparsable"],
    ];
    for (const [stdout, code, expected] of cases) {
      const child = fakeChild();
      const done = runClaude(INPUT, { ...DEPS, spawn: spawnReturning(child) });
      child.stderr.write("stderr tail");
      await finish(child, stdout, code);

      const result = await done;
      expect(result.ok, expected).toBe(false);
      expect(result.ok ? "" : result.error).toContain(expected);
    }
  });

  it("AI_AC_16 kills the process and reports 'timeout' when it runs too long", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      const child = fakeChild();
      const done = runClaude({ ...INPUT, timeoutMs: 120_000 }, { ...DEPS, spawn: spawnReturning(child) });
      await new Promise((resolve) => setImmediate(resolve));

      await vi.advanceTimersByTimeAsync(120_001);

      await expect(done).resolves.toEqual({ ok: false, error: "timeout" });
      expect(child.kill).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a spawn error (claude not installed) as a failure", async () => {
    const child = fakeChild();
    const done = runClaude(INPUT, { ...DEPS, spawn: spawnReturning(child) });
    await new Promise((resolve) => setImmediate(resolve));
    child.emit("error", new Error("spawn claude ENOENT"));

    const result = await done;
    expect(result.ok).toBe(false);
    expect(result.ok ? "" : result.error).toContain("ENOENT");
  });
});
