import { fileURLToPath } from "node:url";

export interface WorkerConfig {
  kafkaBrokers: string[];
  kafkaSasl?: { mechanism: "plain"; username: string; password: string };
  topic: string;
  groupId: string;
  internalUrl: string;
  workerSecret: string;
  s3: { endpoint: string; region: string; bucket: string; accessKeyId: string; secretAccessKey: string };
  claudeBin: string;
  claudeTimeoutMs: number;
  maxTurns: number;
  /** claude 가 자식 프로세스로 띄우는 MCP 서버(stdio)의 경로. */
  mcpServerPath: string;
}

type Env = Record<string, string | undefined>;

const REQUIRED = [
  "KAFKA_BOOTSTRAP_SERVERS",
  "AI_INTERNAL_URL",
  "AI_WORKER_SECRET",
  "S3_ENDPOINT",
  "S3_BUCKET",
  "S3_ACCESS_KEY",
  "S3_SECRET_KEY",
] as const;

/**
 * 환경변수에서 설정을 읽는다. 빠진 것이 있으면 시작하지 않고 빠진 이름을 전부 알려 준다 — 반쯤 설정된 워커가
 * 떠서 요청만 붙잡고 있는 것보다 부팅 실패가 낫다. 인증은 구독 토큰(CLAUDE_CODE_OAUTH_TOKEN)이 우선이고
 * ANTHROPIC_API_KEY 는 대안이다.
 */
export function loadConfig(env: Env): WorkerConfig {
  const missing: string[] = REQUIRED.filter((name) => !env[name]);
  if (!env.CLAUDE_CODE_OAUTH_TOKEN && !env.ANTHROPIC_API_KEY) {
    missing.push("CLAUDE_CODE_OAUTH_TOKEN (or ANTHROPIC_API_KEY)");
  }
  if (missing.length > 0) {
    throw new Error(`ai-worker is missing required environment variables: ${missing.join(", ")}`);
  }

  const get = (name: (typeof REQUIRED)[number]) => env[name] as string;
  const sasl = env.KAFKA_SASL_USERNAME && env.KAFKA_SASL_PASSWORD
    ? { mechanism: "plain" as const, username: env.KAFKA_SASL_USERNAME, password: env.KAFKA_SASL_PASSWORD }
    : undefined;

  return {
    kafkaBrokers: get("KAFKA_BOOTSTRAP_SERVERS").split(",").map((b) => b.trim()).filter(Boolean),
    ...(sasl ? { kafkaSasl: sasl } : {}),
    topic: env.AI_TOPIC ?? "ai.request.v1",
    groupId: env.AI_GROUP_ID ?? "ai-worker",
    internalUrl: get("AI_INTERNAL_URL"),
    workerSecret: get("AI_WORKER_SECRET"),
    s3: {
      endpoint: get("S3_ENDPOINT"),
      region: env.S3_REGION ?? "ap-northeast-2",
      bucket: get("S3_BUCKET"),
      accessKeyId: get("S3_ACCESS_KEY"),
      secretAccessKey: get("S3_SECRET_KEY"),
    },
    claudeBin: env.CLAUDE_BIN ?? "claude",
    claudeTimeoutMs: Number(env.CLAUDE_TIMEOUT_MS ?? 120_000),
    maxTurns: Number(env.CLAUDE_MAX_TURNS ?? 12),
    mcpServerPath: env.MCP_SERVER_PATH ?? fileURLToPath(new URL("./mcp/server.js", import.meta.url)),
  };
}
