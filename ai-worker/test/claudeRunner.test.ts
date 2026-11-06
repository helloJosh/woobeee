import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import { describe, expect, it, vi } from "vitest";
import { buildClaudeEnv, runClaude, type SpawnFn } from "../src/claudeRunner.js";

type FakeChild = EventEmitter & { stdout: PassThrough; stderr: PassThrough; kill: ReturnType<typeof vi.fn> };

function fakeChild(): FakeChild {
  const child = new EventEmitter() as FakeChild;
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.kill = vi.fn();
  return child;
}

function spawnReturning(child: FakeChild): SpawnFn & ReturnType<typeof vi.fn> {
  return vi.fn(() => child) as unknown as SpawnFn & ReturnType<typeof vi.fn>;
}

const INPUT = { prompt: "이미지를 보고 제안해줘", mcpConfigPath: "/tmp/w/mcp.json", cwd: "/tmp/w", timeoutMs: 1000, maxTurns: 12 };
const ENV = { PATH: "/usr/bin", HOME: "/home/worker", CLAUDE_CODE_OAUTH_TOKEN: "tok", ANTHROPIC_API_KEY: "key", AI_WORKER_SECRET: "s3cret", KAFKA_BOOTSTRAP_SERVERS: "k:1" };

describe("runClaude", () => {
  it("AI_AC_17 spawns claude with exactly the headless arguments and the temp dir as cwd", async () => {
    const child = fakeChild();
    const spawn = spawnReturning(child);
    const done = runClaude(INPUT, { spawn, env: ENV, bin: "claude" });
    child.stdout.end(JSON.stringify({ type: "result", is_error: false, result: "끝" }));
    child.emit("close", 0);
    await done;

    expect(spawn).toHaveBeenCalledTimes(1);
    const [bin, args, options] = spawn.mock.calls[0] as [string, string[], { cwd: string; env: Record<string, string> }];
    expect(bin).toBe("claude");
    expect(args).toEqual([
      "-p", INPUT.prompt,
      "--mcp-config", INPUT.mcpConfigPath,
      "--allowedTools", "mcp__woobeee", "Read",
      "--output-format", "json",
      "--max-turns", "12",
    ]);
    expect(options.cwd).toBe("/tmp/w");
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

  it("returns the result text on success", async () => {
    const child = fakeChild();
    const done = runClaude(INPUT, { spawn: spawnReturning(child), env: ENV });
    child.stdout.end(JSON.stringify({ type: "result", subtype: "success", is_error: false, result: "상품 3개를 제안했습니다" }));
    child.emit("close", 0);

    await expect(done).resolves.toEqual({ ok: true, summary: "상품 3개를 제안했습니다" });
  });

  it("reports an error result, a non-zero exit and unparsable output as failures", async () => {
    const cases: Array<[string, number, string]> = [
      [JSON.stringify({ type: "result", is_error: true, result: "rate limited" }), 0, "rate limited"],
      ["", 1, "exit code 1"],
      ["not json at all", 0, "unparsable"],
    ];
    for (const [stdout, code, expected] of cases) {
      const child = fakeChild();
      const done = runClaude(INPUT, { spawn: spawnReturning(child), env: ENV });
      child.stderr.write("stderr tail");
      child.stdout.end(stdout);
      child.emit("close", code);

      const result = await done;
      expect(result.ok).toBe(false);
      expect(result.ok ? "" : result.error).toContain(expected);
    }
  });

  it("AI_AC_16 kills the process and reports 'timeout' when it runs too long", async () => {
    vi.useFakeTimers();
    try {
      const child = fakeChild();
      const done = runClaude({ ...INPUT, timeoutMs: 120_000 }, { spawn: spawnReturning(child), env: ENV });

      await vi.advanceTimersByTimeAsync(120_001);

      await expect(done).resolves.toEqual({ ok: false, error: "timeout" });
      expect(child.kill).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a spawn error (claude not installed) as a failure", async () => {
    const child = fakeChild();
    const done = runClaude(INPUT, { spawn: spawnReturning(child), env: ENV });
    child.emit("error", new Error("spawn claude ENOENT"));

    const result = await done;
    expect(result.ok).toBe(false);
    expect(result.ok ? "" : result.error).toContain("ENOENT");
  });
});
