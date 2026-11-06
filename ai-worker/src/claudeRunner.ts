import { spawn as nodeSpawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { extname } from "node:path";

export type SpawnFn = typeof nodeSpawn;

export interface RunClaudeInput {
  /** 지시문. 이미지와 함께 stdin 으로 간다 — argv 에 싣지 않는다(길이 제한·ps 노출을 피한다). */
  prompt: string;
  /** 내려받은 이미지 파일. claude 에게 파일 경로를 주지 않고 내용(base64)을 stdin 으로 넘긴다. */
  imagePaths: string[];
  mcpConfigPath: string;
  /** 빈 작업 디렉터리. claude 는 파일을 읽는 도구가 없으므로 여기서 읽을 것도 없다. */
  cwd: string;
  timeoutMs: number;
  maxTurns: number;
}

export type RunClaudeResult = { ok: true; summary: string } | { ok: false; error: string };

export interface RunClaudeDeps {
  spawn?: SpawnFn;
  env?: Record<string, string | undefined>;
  bin?: string;
  readFile?: (path: string) => Promise<Buffer>;
}

const STDERR_TAIL = 500;

/**
 * claude 프로세스에 넘기는 환경 — PATH, HOME 과 인증 하나뿐이다. 워커 시크릿(AI_WORKER_SECRET)과 Kafka·S3 설정은
 * 넘기지 않는다: claude 는 프롬프트 인젝션에 노출되는 프로세스라 가진 것이 적어야 한다. 구독 토큰이 있으면 그것을 쓰고,
 * API 키는 토큰이 없을 때만 쓴다.
 */
export function buildClaudeEnv(source: Record<string, string | undefined>): Record<string, string> {
  const env: Record<string, string> = {};
  for (const name of ["PATH", "HOME"]) {
    const value = source[name];
    if (value) env[name] = value;
  }
  if (source.CLAUDE_CODE_OAUTH_TOKEN) {
    env.CLAUDE_CODE_OAUTH_TOKEN = source.CLAUDE_CODE_OAUTH_TOKEN;
  } else if (source.ANTHROPIC_API_KEY) {
    env.ANTHROPIC_API_KEY = source.ANTHROPIC_API_KEY;
  }
  return env;
}

const MEDIA_TYPES: Record<string, string> = {
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
};

function mediaTypeOf(path: string): string {
  return MEDIA_TYPES[extname(path).toLowerCase()] ?? "image/png";
}

/**
 * claude 에게 줄 사용자 메시지(stream-json 한 줄). 이미지는 base64 블록으로, 지시문은 마지막 텍스트 블록으로 담는다.
 * 이렇게 하면 claude 에게 `Read` 를 줄 이유가 없다 — 파일을 읽는 도구가 하나라도 있으면 프롬프트 인젝션이
 * /proc/self/environ 으로 OAuth 토큰과 워커 설정을 읽어 낼 수 있다(리뷰 I2).
 */
async function buildUserMessage(input: RunClaudeInput, readFile: (path: string) => Promise<Buffer>): Promise<string> {
  const images = await Promise.all(
    input.imagePaths.map(async (path) => ({
      type: "image",
      source: { type: "base64", media_type: mediaTypeOf(path), data: (await readFile(path)).toString("base64") },
    })),
  );
  return JSON.stringify({
    type: "user",
    message: { role: "user", content: [...images, { type: "text", text: input.prompt }] },
  });
}

/**
 * `claude -p` 를 헤드리스 stream-json 으로 돌린다. 허용 도구는 MCP 서버 woobeee 하나뿐이다(AI-AC-17) —
 * 파일·셸 도구는 없다. 시간이 넘으면 프로세스를 죽이고 {@code timeout} 으로 실패를 알린다(AI-AC-16).
 */
export async function runClaude(input: RunClaudeInput, deps: RunClaudeDeps = {}): Promise<RunClaudeResult> {
  const spawn = deps.spawn ?? nodeSpawn;

  let message: string;
  try {
    message = await buildUserMessage(input, deps.readFile ?? ((path) => readFile(path)));
  } catch (error) {
    return { ok: false, error: `could not read an image: ${error instanceof Error ? error.message : String(error)}` };
  }

  const args = [
    "-p",
    "--input-format", "stream-json",
    "--output-format", "stream-json",
    "--verbose",
    "--mcp-config", input.mcpConfigPath,
    // 이 설정 파일의 MCP 서버만 쓴다 — 작업 환경에 다른 MCP 설정이 있어도 도구가 늘지 않는다.
    "--strict-mcp-config",
    "--allowedTools", "mcp__woobeee",
    "--max-turns", String(input.maxTurns),
  ];

  return new Promise<RunClaudeResult>((resolve) => {
    let settled = false;
    const settle = (result: RunClaudeResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    };

    const child = spawn(deps.bin ?? "claude", args, {
      cwd: input.cwd,
      env: buildClaudeEnv(deps.env ?? process.env),
      stdio: ["pipe", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
    child.stderr?.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
    // claude 가 일찍 죽으면 stdin 쓰기가 EPIPE 가 된다 — 실패는 close/error 이벤트가 알리므로 여기서는 삼킨다.
    child.stdin?.on("error", () => {});
    child.stdin?.end(`${message}\n`);

    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      settle({ ok: false, error: "timeout" });
    }, input.timeoutMs);

    child.on("error", (error: Error) => settle({ ok: false, error: `could not run claude: ${error.message}` }));
    child.on("close", (code: number | null) => {
      const parsed = parseResult(stdout);
      if (parsed?.isError) {
        settle({ ok: false, error: parsed.result || "claude reported an error" });
      } else if (code !== 0) {
        settle({ ok: false, error: `exit code ${code}: ${stderr.slice(-STDERR_TAIL)}`.trim() });
      } else if (parsed) {
        settle({ ok: true, summary: parsed.result });
      } else {
        settle({ ok: false, error: "unparsable output from claude" });
      }
    });
  });
}

/** stream-json 출력의 마지막 {@code result} 이벤트. 그 줄이 없거나 읽을 수 없으면 null. */
function parseResult(stdout: string): { isError: boolean; result: string } | null {
  let found: { isError: boolean; result: string } | null = null;
  for (const line of stdout.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      const event = JSON.parse(trimmed) as { type?: string; is_error?: boolean; result?: unknown };
      if (event.type === "result") {
        found = { isError: event.is_error === true, result: typeof event.result === "string" ? event.result : "" };
      }
    } catch {
      // stream-json 이 아닌 줄(경고 등)은 건너뛴다
    }
  }
  return found;
}
