import { assert, assertEquals } from "@std/assert";

async function readRepoFile(relativeFromProjectRoot: string): Promise<string> {
  // src/ -> project root
  const url = new URL(`../${relativeFromProjectRoot}`, import.meta.url);
  return await Deno.readTextFile(url);
}

Deno.test("infra: docker-compose never publishes gateway 8080 to a public interface", async () => {
  const compose = await readRepoFile("docker-compose.yml");

  // A public publish would be a `ports:` mapping like "8080:8080" (binds 0.0.0.0).
  // Only `expose:` (internal network) or an explicit loopback publish is allowed.
  const publicPublish = /["']?0\.0\.0\.0:8080:8080["']?/.test(compose) ||
    /^\s*-\s*["']?8080:8080["']?\s*$/m.test(compose);
  assertEquals(
    publicPublish,
    false,
    "docker-compose.yml must not publish 8080 to the host/public interface",
  );

  assert(
    /expose:\s*\n\s*-\s*["']?8080["']?/.test(compose),
    "docker-compose.yml should expose 8080 only on the internal network",
  );
});

Deno.test("infra: Dockerfile copies deno.lock and builds/runs with --frozen", async () => {
  const dockerfile = await readRepoFile("Dockerfile");
  assert(/COPY[^\n]*deno\.lock/.test(dockerfile), "Dockerfile must COPY deno.lock");
  assert(
    /deno\s+cache\s+--frozen/.test(dockerfile),
    "Dockerfile must cache dependencies with --frozen",
  );
  assert(
    /deno[^\n]*run[^\n]*--frozen/.test(dockerfile),
    "Dockerfile CMD must run with --frozen",
  );
});

Deno.test("infra: deno.lock exists", async () => {
  const lock = await readRepoFile("deno.lock");
  assert(lock.trim().length > 0, "deno.lock must be present and non-empty");
});
