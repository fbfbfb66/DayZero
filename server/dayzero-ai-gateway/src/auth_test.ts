import { assertEquals } from "@std/assert";
import { exportJWK, generateKeyPair, type JWK, SignJWT } from "jose";
import { resetJWKSetForTests, verifyAccessToken } from "./auth.ts";
import { createLoggerStub, createTestConfig } from "./test_helpers.ts";

const logger = createLoggerStub() as never;

const ISSUER = "https://test-project.supabase.co/auth/v1";
const AUDIENCE = "authenticated";

type TestKey = { privateKey: CryptoKey; jwks: { keys: JWK[] } };

async function createEs256Jwks(kid = "es256-key-1"): Promise<TestKey> {
  const { privateKey, publicKey } = await generateKeyPair("ES256", {
    extractable: true,
  });
  const jwk = await exportJWK(publicKey);
  jwk.kid = kid;
  jwk.use = "sig";
  jwk.alg = "ES256";
  return { privateKey, jwks: { keys: [jwk] } };
}

function mockJwksEndpoint(
  jwks: Record<string, unknown>,
  options: { status?: number; body?: string } = {},
): () => void {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      if (options.body !== undefined) {
        return Promise.resolve(
          new Response(options.body, { status: options.status ?? 200 }),
        );
      }
      return Promise.resolve(
        new Response(JSON.stringify(jwks), {
          status: options.status ?? 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }
    return originalFetch(input);
  };
  return () => {
    globalThis.fetch = originalFetch;
  };
}

async function signEs256(
  privateKey: CryptoKey,
  options: {
    kid?: string;
    issuer?: string;
    audience?: string;
    expiration?: string;
    sub?: string;
    includeExp?: boolean;
  } = {},
): Promise<string> {
  const builder = new SignJWT({ sub: options.sub ?? "user-123" })
    .setProtectedHeader({ alg: "ES256", kid: options.kid ?? "es256-key-1" })
    .setIssuedAt()
    .setIssuer(options.issuer ?? ISSUER)
    .setAudience(options.audience ?? AUDIENCE);

  if (options.includeExp !== false) {
    builder.setExpirationTime(options.expiration ?? "1h");
  }
  return await builder.sign(privateKey);
}

Deno.test("auth: missing header returns 401 AUTH_HEADER_MISSING", async () => {
  resetJWKSetForTests();
  const config = createTestConfig({ enableAuth: true });
  const result = await verifyAccessToken(null, config, logger);
  assertEquals(result.ok, false);
  if (!result.ok) {
    assertEquals(result.status, 401);
    assertEquals(result.code, "AUTH_HEADER_MISSING");
  }
});

Deno.test("auth: non-Bearer scheme returns 401 AUTH_SCHEME_INVALID", async () => {
  resetJWKSetForTests();
  const config = createTestConfig({ enableAuth: true });
  const result = await verifyAccessToken("Basic abc", config, logger);
  assertEquals(result.ok, false);
  if (!result.ok) {
    assertEquals(result.status, 401);
    assertEquals(result.code, "AUTH_SCHEME_INVALID");
  }
});

Deno.test("auth: empty Bearer token returns 401 JWT_EMPTY", async () => {
  resetJWKSetForTests();
  const config = createTestConfig({ enableAuth: true });
  const result = await verifyAccessToken("Bearer   ", config, logger);
  assertEquals(result.ok, false);
  if (!result.ok) {
    assertEquals(result.status, 401);
    assertEquals(result.code, "JWT_EMPTY");
  }
});

Deno.test("auth: malformed token returns 401 JWT_PARSE_FAILED", async () => {
  resetJWKSetForTests();
  const config = createTestConfig({ enableAuth: true });
  const result = await verifyAccessToken("Bearer not-a-jwt", config, logger);
  assertEquals(result.ok, false);
  if (!result.ok) {
    assertEquals(result.status, 401);
    assertEquals(result.code, "JWT_PARSE_FAILED");
  }
});

Deno.test("auth: alg other than ES256 returns 401 JWT_ALG_INVALID", async () => {
  resetJWKSetForTests();
  const secret = new TextEncoder().encode("legacy-shared-jwt-secret");
  const hsToken = await new SignJWT({ role: "anon" })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setExpirationTime("1h")
    .sign(secret);
  const { jwks } = await createEs256Jwks();
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${hsToken}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_ALG_INVALID");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: missing kid returns 401 JWT_KID_MISSING", async () => {
  resetJWKSetForTests();
  const { privateKey } = await createEs256Jwks();
  const tokenWithoutKid = await new SignJWT({ sub: "user-123" })
    .setProtectedHeader({ alg: "ES256" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setExpirationTime("1h")
    .sign(privateKey);
  const { jwks } = await createEs256Jwks();
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${tokenWithoutKid}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_KID_MISSING");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: unknown kid refreshes JWKS and succeeds when key appears", async () => {
  resetJWKSetForTests();
  const key1 = await createEs256Jwks("key-1");
  const key2 = await createEs256Jwks("key-2");
  const token = await signEs256(key2.privateKey, { kid: "key-2" });

  let fetchCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      fetchCount++;
      const jwks = fetchCount === 1 ? key1.jwks : key2.jwks;
      return Promise.resolve(
        new Response(JSON.stringify(jwks), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, true);
    assertEquals(fetchCount, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("auth: unknown kid refresh still fails returns JWT_KEY_NOT_FOUND", async () => {
  resetJWKSetForTests();
  const key1 = await createEs256Jwks("key-1");
  const key2 = await createEs256Jwks("key-2");
  const token = await signEs256(key2.privateKey, { kid: "key-3" });

  const restore = mockJwksEndpoint(key1.jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_KEY_NOT_FOUND");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: wrong signature returns 401 JWT_SIGNATURE_INVALID", async () => {
  resetJWKSetForTests();
  const attackerKey = await createEs256Jwks("attacker-key");
  const legitimateKey = await createEs256Jwks("legit-key");
  const token = await signEs256(attackerKey.privateKey, { kid: "legit-key" });
  const restore = mockJwksEndpoint(legitimateKey.jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_SIGNATURE_INVALID");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: missing exp returns 401 JWT_CLAIM_MISSING", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { includeExp: false });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_CLAIM_MISSING");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: expired token returns 401 JWT_EXPIRED", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { expiration: "-1h" });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_EXPIRED");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: missing sub returns 403 JWT_CLAIM_MISSING", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await new SignJWT({})
    .setProtectedHeader({ alg: "ES256", kid: "es256-key-1" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setExpirationTime("1h")
    .sign(privateKey);
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 403);
      assertEquals(result.code, "JWT_CLAIM_MISSING");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: empty sub returns 403 JWT_CLAIM_MISSING", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { sub: "   " });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 403);
      assertEquals(result.code, "JWT_CLAIM_MISSING");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: wrong issuer returns 401 JWT_ISSUER_INVALID", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { issuer: "https://evil.com/auth/v1" });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_ISSUER_INVALID");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: wrong audience returns 401 JWT_AUDIENCE_INVALID", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { audience: "wrong-aud" });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_AUDIENCE_INVALID");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: audience as string matching expected passes", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey, { audience: "authenticated" });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, true);
  } finally {
    restore();
  }
});

Deno.test("auth: audience as array containing expected passes", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await new SignJWT({ sub: "user-123" })
    .setProtectedHeader({ alg: "ES256", kid: "es256-key-1" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(["authenticated", "another-aud"])
    .setExpirationTime("1h")
    .sign(privateKey);
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, true);
  } finally {
    restore();
  }
});

Deno.test("auth: audience as array not containing expected returns 401 JWT_AUDIENCE_INVALID", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await new SignJWT({ sub: "user-123" })
    .setProtectedHeader({ alg: "ES256", kid: "es256-key-1" })
    .setIssuedAt()
    .setIssuer(ISSUER)
    .setAudience(["wrong-aud", "another-aud"])
    .setExpirationTime("1h")
    .sign(privateKey);
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_AUDIENCE_INVALID");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: token with zero-second clock tolerance rejects just-expired token", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  // Expiration in the past triggers immediate rejection with 0s tolerance.
  const token = await signEs256(privateKey, { expiration: "-1s" });
  const restore = mockJwksEndpoint(jwks);
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWT_EXPIRED");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: JWKS network failure returns 401 JWKS_UNAVAILABLE", async () => {
  resetJWKSetForTests();
  const { privateKey } = await createEs256Jwks();
  const token = await signEs256(privateKey);
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      return Promise.reject(new Error("network down"));
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWKS_UNAVAILABLE");
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("auth: malformed JWKS returns 401 JWKS_UNAVAILABLE", async () => {
  resetJWKSetForTests();
  const { privateKey } = await createEs256Jwks();
  const token = await signEs256(privateKey);
  const restore = mockJwksEndpoint({}, { body: "not-json" });
  try {
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, false);
    if (!result.ok) {
      assertEquals(result.status, 401);
      assertEquals(result.code, "JWKS_UNAVAILABLE");
    }
  } finally {
    restore();
  }
});

Deno.test("auth: valid ES256 token with correct issuer/aud passes", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const restore = mockJwksEndpoint(jwks);
  try {
    const token = await signEs256(privateKey);
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, true);
    if (result.ok) assertEquals(result.payload.sub, "user-123");
  } finally {
    restore();
  }
});

Deno.test("auth: valid token returns stable userIdDigest", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const restore = mockJwksEndpoint(jwks);
  try {
    const token = await signEs256(privateKey, { sub: "a1b2c3d4-e5f6-7890-abcd-ef1234567890" });
    const config = createTestConfig({ enableAuth: true });
    const result = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(result.ok, true);
    if (result.ok) {
      assertEquals(result.userIdDigest.startsWith("u_"), true);
      assertEquals(result.userIdDigest.length, 2 + 16);
    }
  } finally {
    restore();
  }
});

Deno.test("auth: disabled auth returns anonymous userIdDigest", async () => {
  resetJWKSetForTests();
  const config = createTestConfig({ enableAuth: false });
  const result = await verifyAccessToken(null, config, logger);
  assertEquals(result.ok, true);
  if (result.ok) assertEquals(result.userIdDigest, "anonymous");
});

Deno.test("auth: concurrent initial JWKS requests share a single fetch", async () => {
  resetJWKSetForTests();
  const key1 = await createEs256Jwks("key-1");
  const token = await signEs256(key1.privateKey, { kid: "key-1" });

  let fetchCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      fetchCount++;
      return Promise.resolve(
        new Response(JSON.stringify(key1.jwks), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const results = await Promise.all(
      Array.from({ length: 10 }, () => verifyAccessToken(`Bearer ${token}`, config, logger)),
    );
    assertEquals(results.every((r) => r.ok), true);
    assertEquals(fetchCount, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("auth: concurrent unknown kid requests share a single refresh", async () => {
  resetJWKSetForTests();
  const key1 = await createEs256Jwks("key-1");
  const key2 = await createEs256Jwks("key-2");
  const token = await signEs256(key2.privateKey, { kid: "key-2" });

  let fetchCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      fetchCount++;
      const jwks = fetchCount === 1 ? key1.jwks : key2.jwks;
      return Promise.resolve(
        new Response(JSON.stringify(jwks), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const results = await Promise.all(
      Array.from({ length: 10 }, () => verifyAccessToken(`Bearer ${token}`, config, logger)),
    );
    assertEquals(results.every((r) => r.ok), true);
    assertEquals(fetchCount, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("auth: JWKS failure enters cooldown and returns JWKS_UNAVAILABLE", async () => {
  resetJWKSetForTests();
  const { privateKey } = await createEs256Jwks();
  const token = await signEs256(privateKey);

  let fetchCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      fetchCount++;
      return Promise.reject(new Error("network down"));
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const first = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(first.ok, false);
    if (!first.ok) assertEquals(first.code, "JWKS_UNAVAILABLE");

    const second = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(second.ok, false);
    if (!second.ok) assertEquals(second.code, "JWKS_UNAVAILABLE");

    // Both requests share the first fetch attempt; cooldown prevents another.
    assertEquals(fetchCount, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("auth: JWKS rejected Promise is cleared so cooldown can expire and fetch retries", async () => {
  resetJWKSetForTests();
  const { privateKey, jwks } = await createEs256Jwks();
  const token = await signEs256(privateKey);

  let fetchCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input: string | URL | Request): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/.well-known/jwks.json")) {
      fetchCount++;
      if (fetchCount === 1) {
        return Promise.reject(new Error("network down"));
      }
      return Promise.resolve(
        new Response(JSON.stringify(jwks), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }
    return originalFetch(input);
  };
  try {
    const config = createTestConfig({ enableAuth: true });
    const first = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(first.ok, false);

    // Clear the cooldown failure state so a fresh fetch is attempted.
    resetJWKSetForTests();

    const second = await verifyAccessToken(`Bearer ${token}`, config, logger);
    assertEquals(second.ok, true);
    assertEquals(fetchCount, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
