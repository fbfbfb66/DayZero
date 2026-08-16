import { StructuredLogger } from "./logger.ts";

export const TITLE_SYSTEM_PROMPT = `你是聊天会话标题生成器。

请根据用户发来的第一条消息，生成一个简洁、自然、能够概括对话目的的标题。

规则：
1. 只输出标题本身，不要解释。
2. 不要加“标题：”、引号、书名号、Markdown 或句号。
3. 中文标题通常为 6 到 16 个汉字。
4. 英文标题通常为 3 到 8 个单词。
5. 保持用户输入的主要语言。
6. 不要机械复制整句，要概括核心主题或意图。
7. 不要加入用户没有提到的信息。
8. 避免在标题中完整暴露手机号、邮箱、身份证号、精确地址等敏感信息。
9. 不要使用换行。
10. 如果第一条消息包含图片，结合图片内容概括标题；纯图片消息也要生成标题。`;

type WorkerConfig = {
  supabaseUrl: string;
  serviceRoleKey: string;
  kimiApiUrl: string;
  kimiApiKey: string;
  titleModel: string;
  titleTimeoutMs: number;
  concurrency: number;
  maxAttempts: number;
  retryBaseDelayMs: number;
  pollIntervalMs: number;
  leaseSeconds: number;
  workerId: string;
};

export type TitleJob = {
  id: string;
  user_id: string;
  conversation_id: string;
  first_user_message_id: string;
  first_user_text: string;
  first_user_media_count: number;
  attempt_count: number;
};

class TitleWorkerError extends Error {
  constructor(
    readonly code: string,
    readonly retryable: boolean,
  ) {
    super(code);
  }
}

function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function positiveInt(name: string, fallback: number): number {
  const raw = Deno.env.get(name);
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`Invalid ${name}`);
  }
  return parsed;
}

export function loadTitleWorkerConfig(): WorkerConfig {
  return {
    supabaseUrl: env("SUPABASE_URL").replace(/\/+$/, ""),
    serviceRoleKey: env("SUPABASE_SERVICE_ROLE_KEY"),
    kimiApiUrl: env("KIMI_API_URL"),
    kimiApiKey: env("KIMI_API_KEY"),
    titleModel: env("TITLE_MODEL"),
    titleTimeoutMs: positiveInt("TITLE_TIMEOUT_MS", 12_000),
    concurrency: Math.min(20, positiveInt("TITLE_WORKER_CONCURRENCY", 2)),
    maxAttempts: positiveInt("TITLE_MAX_ATTEMPTS", 5),
    retryBaseDelayMs: positiveInt("TITLE_RETRY_BASE_DELAY_MS", 30_000),
    pollIntervalMs: positiveInt("TITLE_POLL_INTERVAL_MS", 2_000),
    leaseSeconds: positiveInt("TITLE_LEASE_SECONDS", 120),
    workerId: Deno.env.get("TITLE_WORKER_ID") ??
      `title-worker-${crypto.randomUUID().slice(0, 8)}`,
  };
}

export function sanitizeGeneratedTitle(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const firstLine = raw.split(/\r?\n/, 1)[0]
    // deno-lint-ignore no-control-regex
    .replace(/[\u0000-\u001f\u007f]/g, "")
    .trim()
    .replace(/^(?:标题|title)\s*[:：]\s*/i, "")
    .trim()
    .replace(/[。.!！?？]+$/g, "")
    .trim()
    .replace(/^[“”"'《》「」『』]+|[“”"'《》「」『』]+$/g, "")
    .trim()
    .replace(/[。.!！?？]+$/g, "")
    .trim();
  if (!firstLine) return null;
  const chars = Array.from(firstLine);
  if (chars.length > 48) return null;
  if (/^```|[*#]{2,}/.test(firstLine)) return null;
  if (/\b[\w.%+-]+@[\w.-]+\.[a-z]{2,}\b/i.test(firstLine)) return null;
  if (/(?:\d[\s-]?){7,}/.test(firstLine)) return null;
  if (/\b\d{17}[\dXx]\b/.test(firstLine)) return null;
  return firstLine;
}

async function rpc<T>(
  config: WorkerConfig,
  name: string,
  body: Record<string, unknown>,
): Promise<T> {
  const response = await fetch(`${config.supabaseUrl}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "apikey": config.serviceRoleKey,
      "Authorization": `Bearer ${config.serviceRoleKey}`,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(8_000),
  });
  if (!response.ok) {
    throw new TitleWorkerError(
      `RPC_HTTP_${response.status}`,
      response.status === 408 || response.status === 429 || response.status >= 500,
    );
  }
  return await response.json() as T;
}

async function claimJobs(config: WorkerConfig): Promise<TitleJob[]> {
  return await rpc<TitleJob[]>(config, "claim_ai_conversation_title_jobs", {
    p_worker_id: config.workerId,
    p_limit: config.concurrency,
    p_lease_seconds: config.leaseSeconds,
    p_max_attempts: config.maxAttempts,
  });
}

const MAX_TITLE_IMAGES = 3;

type MediaAssetRow = {
  thumbnail_object_path: string | null;
  master_object_path: string | null;
};

function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function isRetryableHttp(status: number): boolean {
  return status === 408 || status === 429 || status >= 500;
}

async function fetchJobMediaPaths(config: WorkerConfig, job: TitleJob): Promise<string[]> {
  const required = Math.min(Math.max(job.first_user_media_count, 0), MAX_TITLE_IMAGES);
  if (required === 0) return [];
  const url = new URL(`${config.supabaseUrl}/rest/v1/media_assets`);
  url.searchParams.set("select", "thumbnail_object_path,master_object_path");
  url.searchParams.set("source_message_id", `eq.${job.first_user_message_id}`);
  url.searchParams.set("user_id", `eq.${job.user_id}`);
  url.searchParams.set("deleted_at", "is.null");
  url.searchParams.set("order", "conversation_order.asc");
  url.searchParams.set("limit", String(required));
  const response = await fetch(url, {
    headers: {
      "apikey": config.serviceRoleKey,
      "Authorization": `Bearer ${config.serviceRoleKey}`,
    },
    signal: AbortSignal.timeout(8_000),
  });
  if (!response.ok) {
    throw new TitleWorkerError(
      `MEDIA_QUERY_HTTP_${response.status}`,
      isRetryableHttp(response.status),
    );
  }
  const rows = await response.json() as MediaAssetRow[];
  const paths = rows
    .map((row) => row.thumbnail_object_path ?? row.master_object_path)
    .filter((path): path is string => typeof path === "string" && path.length > 0);
  if (paths.length < required) {
    // Media sync uploads bytes before metadata, so a shortfall means the upload
    // is still in flight — retry later instead of generating an image-blind title.
    throw new TitleWorkerError("MEDIA_NOT_READY", true);
  }
  return paths.slice(0, required);
}

async function downloadMediaDataUrl(config: WorkerConfig, path: string): Promise<string> {
  const response = await fetch(
    `${config.supabaseUrl}/storage/v1/object/media-assets/${path}`,
    {
      headers: {
        "apikey": config.serviceRoleKey,
        "Authorization": `Bearer ${config.serviceRoleKey}`,
      },
      signal: AbortSignal.timeout(10_000),
    },
  );
  if (!response.ok) {
    throw new TitleWorkerError(
      `MEDIA_DOWNLOAD_HTTP_${response.status}`,
      isRetryableHttp(response.status) || response.status === 404,
    );
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length === 0) {
    throw new TitleWorkerError("MEDIA_DOWNLOAD_EMPTY", true);
  }
  return `data:image/jpeg;base64,${encodeBase64(bytes)}`;
}

export async function buildTitleUserContent(
  config: WorkerConfig,
  job: TitleJob,
): Promise<unknown> {
  const text = job.first_user_text.trim();
  if (job.first_user_media_count <= 0) return text;
  const paths = await fetchJobMediaPaths(config, job);
  const parts: Array<Record<string, unknown>> = [
    { type: "text", text: text || "（用户只发送了图片，没有文字）" },
  ];
  for (const path of paths) {
    parts.push({
      type: "image_url",
      image_url: { url: await downloadMediaDataUrl(config, path) },
    });
  }
  return parts;
}

async function requestTitle(
  config: WorkerConfig,
  content: unknown,
  strictRetry: boolean,
): Promise<string> {
  let response: Response;
  try {
    response = await fetch(config.kimiApiUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${config.kimiApiKey}`,
      },
      body: JSON.stringify({
        model: config.titleModel,
        messages: [
          {
            role: "system",
            content: strictRetry
              ? `${TITLE_SYSTEM_PROMPT}\n严格重试：输出必须是单行纯标题，最长 48 个字符。`
              : TITLE_SYSTEM_PROMPT,
          },
          { role: "user", content },
        ],
        max_tokens: 64,
        // kimi-k2.6 rejects any temperature other than 0.6 (HTTP 400).
        temperature: 0.6,
        stream: false,
        thinking: { type: "disabled" },
      }),
      signal: AbortSignal.timeout(config.titleTimeoutMs),
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "TimeoutError") {
      throw new TitleWorkerError("MODEL_TIMEOUT", true);
    }
    throw new TitleWorkerError("MODEL_NETWORK", true);
  }

  if (!response.ok) {
    throw new TitleWorkerError(
      `MODEL_HTTP_${response.status}`,
      response.status === 408 || response.status === 429 || response.status >= 500,
    );
  }
  const json = await response.json() as {
    choices?: Array<{ message?: { content?: unknown } }>;
  };
  const title = sanitizeGeneratedTitle(json.choices?.[0]?.message?.content);
  if (!title) throw new TitleWorkerError("MODEL_OUTPUT_INVALID", false);
  return title;
}

async function generateWithStrictRetry(config: WorkerConfig, content: unknown): Promise<string> {
  try {
    return await requestTitle(config, content, false);
  } catch (error) {
    if (error instanceof TitleWorkerError && error.code === "MODEL_OUTPUT_INVALID") {
      return await requestTitle(config, content, true);
    }
    throw error;
  }
}

function retryDelaySeconds(config: WorkerConfig, attemptCount: number): number {
  const delayMs = config.retryBaseDelayMs * 2 ** Math.max(0, attemptCount - 1);
  return Math.max(1, Math.min(86_400, Math.ceil(delayMs / 1_000)));
}

async function finishFailure(
  config: WorkerConfig,
  job: TitleJob,
  error: TitleWorkerError,
): Promise<void> {
  const retry = error.retryable && job.attempt_count < config.maxAttempts;
  await rpc<boolean>(config, "finish_ai_conversation_title_job_failure", {
    p_job_id: job.id,
    p_worker_id: config.workerId,
    p_error_code: retry || job.attempt_count < config.maxAttempts ? error.code : "MAX_ATTEMPTS",
    p_retry: retry,
    p_delay_seconds: retryDelaySeconds(config, job.attempt_count),
  });
}

async function processJob(
  config: WorkerConfig,
  logger: StructuredLogger,
  job: TitleJob,
): Promise<void> {
  const startedAt = performance.now();
  try {
    const content = await buildTitleUserContent(config, job);
    const title = await generateWithStrictRetry(config, content);
    const result = await rpc<string>(config, "complete_ai_conversation_title_job", {
      p_job_id: job.id,
      p_worker_id: config.workerId,
      p_generated_title: title,
    });
    logger.info("title job completed", {
      status: result,
      attemptCount: job.attempt_count,
      titleLength: Array.from(title).length,
      totalMs: Math.round(performance.now() - startedAt),
    });
  } catch (error) {
    const safeError = error instanceof TitleWorkerError
      ? error
      : new TitleWorkerError("INTERNAL_ERROR", true);
    await finishFailure(config, job, safeError);
    logger.warn("title job failed", {
      status: safeError.retryable ? "retry_wait" : "failed_permanent",
      attemptCount: job.attempt_count,
      errorCode: safeError.code,
      totalMs: Math.round(performance.now() - startedAt),
    });
  }
}

export async function runTitleWorker(): Promise<never> {
  const config = loadTitleWorkerConfig();
  const logger = new StructuredLogger("dayzero-title-worker", "info");
  logger.info("title worker starting", { status: "starting" });
  while (true) {
    try {
      const jobs = await claimJobs(config);
      if (jobs.length > 0) {
        await Promise.all(jobs.map((job) => processJob(config, logger, job)));
      } else {
        await new Promise((resolve) => setTimeout(resolve, config.pollIntervalMs));
      }
    } catch {
      logger.warn("title worker poll failed", {
        status: "retry_wait",
        errorCode: "POLL_FAILED",
      });
      await new Promise((resolve) => setTimeout(resolve, config.pollIntervalMs));
    }
  }
}

if (import.meta.main) {
  await runTitleWorker();
}
