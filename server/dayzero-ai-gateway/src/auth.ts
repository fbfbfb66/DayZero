import {
  createLocalJWKSet,
  decodeProtectedHeader,
  type JSONWebKeySet,
  type JWTPayload,
  jwtVerify,
} from "jose";
import type { GatewayConfig } from "./config.ts";
import { digestUserId, type StructuredLogger } from "./logger.ts";

export type AuthErrorCode =
  | "AUTH_HEADER_MISSING"
  | "AUTH_SCHEME_INVALID"
  | "JWT_EMPTY"
  | "JWT_PARSE_FAILED"
  | "JWT_ALG_INVALID"
  | "JWT_KID_MISSING"
  | "JWT_KEY_NOT_FOUND"
  | "JWT_SIGNATURE_INVALID"
  | "JWT_EXPIRED"
  | "JWT_CLAIM_MISSING"
  | "JWT_ISSUER_INVALID"
  | "JWT_AUDIENCE_INVALID"
  | "JWKS_UNAVAILABLE";

export type AuthResult =
  | {
    ok: true;
    payload: JWTPayload;
    userIdDigest: string;
  }
  | {
    ok: false;
    status: 401 | 403;
    code: AuthErrorCode;
    message: string;
  };

type JWKSCache = {
  url: string;
  jwks: JSONWebKeySet;
  fetchedAt: number;
  epoch: number;
};

type JWKSFlightState = {
  promise: Promise<JSONWebKeySet>;
  epoch: number;
};

type JWKSFailureState = {
  failedAt: number;
};

let jwksCache: JWKSCache | null = null;
let jwksFlight: JWKSFlightState | null = null;
let jwksFailure: JWKSFailureState | null = null;
let jwksEpoch = 0;

export const JWKS_FETCH_TIMEOUT_MS = 4_000;
export const JWKS_COOLDOWN_MS = 5_000;

async function fetchJWKS(url: string): Promise<JSONWebKeySet> {
  const response = await fetch(url, {
    cache: "no-store",
    signal: AbortSignal.timeout(JWKS_FETCH_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new Error("JWKS fetch failed");
  }
  return await response.json() as JSONWebKeySet;
}

async function runJWKSFetch(url: string): Promise<JSONWebKeySet> {
  const now = Date.now();
  if (jwksFailure && (now - jwksFailure.failedAt) < JWKS_COOLDOWN_MS) {
    throw new Error("JWKS in cooldown");
  }

  try {
    const jwks = await fetchJWKS(url);
    jwksFailure = null;
    jwksEpoch++;
    jwksCache = { url, jwks, fetchedAt: now, epoch: jwksEpoch };
    return jwks;
  } catch (error) {
    jwksFailure = { failedAt: now };
    throw error;
  }
}

async function getJWKSWithSingleFlight(
  url: string,
  minEpoch: number,
): Promise<JSONWebKeySet> {
  const cached = jwksCache;
  const now = Date.now();
  if (
    cached &&
    cached.url === url &&
    cached.epoch >= minEpoch &&
    (now - cached.fetchedAt) < JWKS_COOLDOWN_MS
  ) {
    return cached.jwks;
  }

  if (jwksFlight && jwksFlight.epoch >= minEpoch) {
    return await jwksFlight.promise;
  }

  const promise = runJWKSFetch(url).finally(() => {
    jwksFlight = null;
  });
  jwksFlight = { promise, epoch: jwksEpoch + 1 };
  return await promise;
}

/**
 * Fetches JWKS with single-flight semantics.
 * @param url - JWKS endpoint.
 * @param allowRefresh - When true, forces a fetch newer than the current cache epoch.
 *   Concurrent refresh requests share the same single-flight fetch.
 */
async function getJWKS(
  url: string,
  allowRefresh: boolean,
): Promise<JSONWebKeySet> {
  const minEpoch = allowRefresh ? (jwksCache?.epoch ?? 0) + 1 : 0;
  return await getJWKSWithSingleFlight(url, minEpoch);
}

function findKeyByKid(jwks: JSONWebKeySet, kid: string) {
  return jwks.keys.find((key) => key.kid === kid);
}

function isJoseError(error: unknown): error is { code: string; claim?: string } {
  return error instanceof Error && "code" in error;
}

function mapJoseError(error: unknown): AuthErrorCode {
  if (!isJoseError(error)) {
    return "JWT_SIGNATURE_INVALID";
  }
  const { code, claim } = error as { code: string; claim?: string };
  // jose error codes are prefixed with "ERR_" and use SCREAMING_SNAKE_CASE in this version.
  const normalized = code.replace(/^ERR_/, "").toLowerCase();
  switch (normalized) {
    case "jose_alg_not_allowed":
      return "JWT_ALG_INVALID";
    case "jose_header_invalid":
      return "JWT_KID_MISSING";
    case "jwk_not_found":
      return "JWT_KEY_NOT_FOUND";
    case "jws_invalid":
    case "jws_signature_verification_failed":
    case "jose_invalid_encoding":
      return "JWT_SIGNATURE_INVALID";
    case "jwt_expired":
      return "JWT_EXPIRED";
    case "jwt_not_before":
      return "JWT_EXPIRED";
    case "jwt_claim_validation_failed":
      if (claim === "iss") return "JWT_ISSUER_INVALID";
      if (claim === "aud") return "JWT_AUDIENCE_INVALID";
      return "JWT_CLAIM_MISSING";
    default:
      return "JWT_SIGNATURE_INVALID";
  }
}

function authFailure(
  status: 401 | 403,
  code: AuthErrorCode,
  message: string,
): AuthResult {
  return { ok: false, status, code, message };
}

export function resetJWKSetForTests(): void {
  jwksCache = null;
  jwksFlight = null;
  jwksFailure = null;
}

/**
 * Clock tolerance for `exp`/`nbf` validation is intentionally 0 seconds.
 * Tokens are rejected as soon as their `exp` is not strictly in the future.
 * This avoids any configurable/loose bypass and matches strict production posture.
 */
const CLOCK_TOLERANCE_SECONDS = 0;

export async function verifyAccessToken(
  header: string | null,
  config: GatewayConfig,
  logger: StructuredLogger,
): Promise<AuthResult> {
  if (!config.enableAuth) {
    return { ok: true, payload: {}, userIdDigest: "anonymous" };
  }

  if (!header) {
    return authFailure(401, "AUTH_HEADER_MISSING", "Missing Authorization header");
  }
  if (!header.startsWith("Bearer ")) {
    return authFailure(401, "AUTH_SCHEME_INVALID", "Authorization header must use Bearer scheme");
  }

  const token = header.slice("Bearer ".length).trim();
  if (!token) {
    return authFailure(401, "JWT_EMPTY", "Empty access token");
  }

  let headerData: { alg?: string; kid?: string };
  try {
    headerData = decodeProtectedHeader(token);
  } catch {
    return authFailure(401, "JWT_PARSE_FAILED", "Malformed access token");
  }

  if (headerData.alg !== "ES256") {
    return authFailure(401, "JWT_ALG_INVALID", "Access token algorithm must be ES256");
  }
  if (!headerData.kid || typeof headerData.kid !== "string") {
    return authFailure(401, "JWT_KID_MISSING", "Access token header is missing kid");
  }

  const kid = headerData.kid;
  const url = config.supabaseJwksUrl;

  let jwks: JSONWebKeySet;
  try {
    jwks = await getJWKS(url, false);
  } catch {
    logger.warn("jwks unavailable", { errorCode: "JWKS_UNAVAILABLE" });
    return authFailure(401, "JWKS_UNAVAILABLE", "Unable to verify access token");
  }

  // Unknown kid: refresh once. The single-flight fetch above ensures concurrent
  // unknown-kid requests share one refresh attempt.
  if (!findKeyByKid(jwks, kid)) {
    try {
      jwks = await getJWKS(url, true);
    } catch {
      logger.warn("jwks unavailable after refresh", { errorCode: "JWKS_UNAVAILABLE" });
      return authFailure(401, "JWKS_UNAVAILABLE", "Unable to verify access token");
    }
    if (!findKeyByKid(jwks, kid)) {
      return authFailure(401, "JWT_KEY_NOT_FOUND", "Access token signing key not found");
    }
  }

  let payload: JWTPayload;
  try {
    const { payload: verified } = await jwtVerify(token, createLocalJWKSet(jwks), {
      issuer: config.supabaseIssuer,
      audience: config.supabaseAudience,
      // Supabase user tokens are signed with the project's asymmetric ES256 signing key
      // (JWKS). Restricting the accepted algorithm prevents `alg` confusion and rejects any
      // HS256 shared-secret token (e.g. the legacy publishable/anon key) outright.
      algorithms: ["ES256"],
      clockTolerance: CLOCK_TOLERANCE_SECONDS,
    });
    payload = verified;
  } catch (error) {
    const code = mapJoseError(error);
    logger.debug("jwt verify failed", { errorCode: code });
    return authFailure(401, code, "Invalid or expired access token");
  }

  if (typeof payload.exp !== "number") {
    return authFailure(401, "JWT_CLAIM_MISSING", "Access token is missing exp");
  }
  const sub = payload.sub;
  if (!sub || typeof sub !== "string" || sub.trim().length === 0) {
    return authFailure(403, "JWT_CLAIM_MISSING", "Access token is missing sub");
  }

  const userIdDigest = await digestUserId(sub);
  logger.debug("jwt verified", { userIdDigest });
  return { ok: true, payload, userIdDigest };
}
