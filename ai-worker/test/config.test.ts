import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";

const BASE = {
  KAFKA_BOOTSTRAP_SERVERS: "woobeee-kafka:29092",
  AI_INTERNAL_URL: "http://app-mvc:8000",
  AI_WORKER_SECRET: "s3cret",
  S3_ENDPOINT: "http://minio:9000",
  S3_BUCKET: "woobeee",
  S3_ACCESS_KEY: "admin",
  S3_SECRET_KEY: "pw",
  CLAUDE_CODE_OAUTH_TOKEN: "tok",
};

describe("loadConfig", () => {
  it("reads the environment and applies the documented defaults", () => {
    const cfg = loadConfig(BASE);

    expect(cfg.kafkaBrokers).toEqual(["woobeee-kafka:29092"]);
    expect(cfg.topic).toBe("ai.request.v1");
    expect(cfg.groupId).toBe("ai-worker");
    expect(cfg.claudeTimeoutMs).toBe(120_000);
    expect(cfg.maxTurns).toBe(12);
    expect(cfg.workerSecret).toBe("s3cret");
  });

  it("refuses to start and names every missing variable", () => {
    expect(() => loadConfig({})).toThrowError(/KAFKA_BOOTSTRAP_SERVERS.*AI_INTERNAL_URL.*AI_WORKER_SECRET/s);
  });

  it("requires some Claude credential — the subscription token or an API key", () => {
    const { CLAUDE_CODE_OAUTH_TOKEN: _omit, ...withoutAuth } = BASE;

    expect(() => loadConfig(withoutAuth)).toThrowError(/CLAUDE_CODE_OAUTH_TOKEN|ANTHROPIC_API_KEY/);
    expect(() => loadConfig({ ...withoutAuth, ANTHROPIC_API_KEY: "key" })).not.toThrow();
  });

  it("splits several brokers and reads optional SASL credentials", () => {
    const cfg = loadConfig({ ...BASE, KAFKA_BOOTSTRAP_SERVERS: "a:1,b:2", KAFKA_SASL_USERNAME: "admin", KAFKA_SASL_PASSWORD: "pw" });

    expect(cfg.kafkaBrokers).toEqual(["a:1", "b:2"]);
    expect(cfg.kafkaSasl).toEqual({ mechanism: "plain", username: "admin", password: "pw" });
  });
});
