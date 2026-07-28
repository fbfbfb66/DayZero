const port = Deno.env.get("PORT") ?? "8080";
const url = `http://localhost:${port}/ready`;

try {
  const response = await fetch(url, { method: "GET" });
  if (response.ok) {
    console.log("healthcheck ok");
    Deno.exit(0);
  }
  console.error("healthcheck failed", response.status);
  Deno.exit(1);
} catch (_error) {
  console.error("healthcheck error");
  Deno.exit(1);
}
