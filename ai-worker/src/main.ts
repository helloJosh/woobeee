import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { S3Client } from "@aws-sdk/client-s3";
import { runClaude } from "./claudeRunner.js";
import { loadConfig } from "./config.js";
import { createKafkaConsumer, runConsumer } from "./consumer.js";
import { handleEvent, type HandlerDeps, type RunInput } from "./handler.js";
import { createInternalApi } from "./internalApi.js";
import { writeMcpConfig } from "./mcpConfig.js";
import { buildPrompt } from "./prompt.js";
import { createImageFetcher } from "./s3Images.js";

const cfg = loadConfig(process.env);

const s3 = new S3Client({
  region: cfg.s3.region,
  endpoint: cfg.s3.endpoint,
  forcePathStyle: true,
  credentials: { accessKeyId: cfg.s3.accessKeyId, secretAccessKey: cfg.s3.secretAccessKey },
});

const deps: HandlerDeps = {
  api: createInternalApi(cfg.internalUrl, cfg.workerSecret),
  fetchImages: createImageFetcher(s3, cfg.s3.bucket),
  mkTmp: () => mkdtemp(join(tmpdir(), "ai-request-")),
  rm: (dir) => rm(dir, { recursive: true, force: true }),
  run: async (input: RunInput) => {
    const mcpConfigPath = await writeMcpConfig(input.dir, {
      requestId: input.requestId,
      attempt: input.attempt,
      internalUrl: cfg.internalUrl,
      workerSecret: cfg.workerSecret,
      serverPath: cfg.mcpServerPath,
    });
    return runClaude(
      {
        prompt: buildPrompt({ text: input.text, imageCount: input.imagePaths.length }),
        imagePaths: input.imagePaths,
        mcpConfigPath,
        cwd: input.dir,
        timeoutMs: cfg.claudeTimeoutMs,
        maxTurns: cfg.maxTurns,
      },
      { bin: cfg.claudeBin },
    );
  },
};

const consumer = createKafkaConsumer(cfg);
await runConsumer(consumer, cfg.topic, (event) => handleEvent(deps, event));
console.log(`[ai-worker] consuming ${cfg.topic} as group ${cfg.groupId}`);

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    consumer.disconnect().finally(() => process.exit(0));
  });
}
