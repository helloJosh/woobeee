import { spawn as nodeSpawn } from "node:child_process";

export type SpawnFn = typeof nodeSpawn;

export interface RunClaudeInput {
  prompt: string;
  mcpConfigPath: string;
  /** 이미지가 있는 임시 폴더. Read 도구가 이 안의 파일을 읽는다. */
  cwd: string;
  timeoutMs: number;
  maxTurns: number;
}

export type RunClaudeResult = { ok: true; summary: string } | { ok: false; error: string };

export interface RunClaudeDeps {
  spawn?: SpawnFn;
  env?: Record<string, string | undefined>;
  bin?: string;
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

/**
 * `claude -p` 를 헤드리스로 돌린다. 허용 도구는 MCP 서버 woobeee 와 Read 뿐이다(AI-AC-17).
 * 시간이 넘으면 프로세스를 죽이고 {@code timeout} 으로 실패를 알린다(AI-AC-16).
 */
export function runClaude(input: RunClaudeInput, deps: RunClaudeDeps = {}): Promise<RunClaudeResult> {
  const spawn = deps.spawn ?? nodeSpawn;
  const args = [
    "-p", input.prompt,
    "--mcp-config", input.mcpConfigPath,
    "--allowedTools", "mcp__woobeee", "Read",
    "--output-format", "json",
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
      stdio: ["ignore", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
    child.stderr?.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });

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

function parseResult(stdout: string): { isError: boolean; result: string } | null {
  try {
    const value = JSON.parse(stdout.trim()) as { is_error?: boolean; result?: unknown };
    return { isError: value.is_error === true, result: typeof value.result === "string" ? value.result : "" };
  } catch {
    return null;
  }
}
