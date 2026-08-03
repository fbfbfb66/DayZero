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
9. 不要使用换行。`;

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
  conversation_id: string;
  first_user_text: string;
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

async function requestTitle(
  config: WorkerConfig,
  text: string,
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
          { role: "user", content: text },
        ],
        max_tokens: 64,
        temperature: 0.2,
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

async function generateWithStrictRetry(config: WorkerConfig, text: string): Promise<string> {
  try {
    return await requestTitle(config, text, false);
  } catch (error) {
    if (error instanceof TitleWorkerError && error.code === "MODEL_OUTPUT_INVALID") {
      return await requestTitle(config, text, true);
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
    p_error_code: retry || job.attempt_count < config.maxAttempts
      ? error.code
      : "MAX_ATTEMPTS",
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
    const title = await generateWithStrictRetry(config, job.first_user_text);
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
