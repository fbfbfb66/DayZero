import { assertEquals } from "@std/assert";
import { handleHealth } from "./health.ts";
import { createTestConfig } from "../test_helpers.ts";

Deno.test("health handler returns ok", async () => {
  const config = createTestConfig();
  const req = new Request("http://localhost/health", { method: "GET" });
  const response = handleHealth(req, config);

  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.status, "ok");
  assertEquals(body.service, "dayzero-ai-gateway");
  assertEquals(typeof body.timestamp, "string");
});
