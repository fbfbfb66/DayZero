import { assertEquals } from "jsr:@std/assert@1";
import {
  MAX_VISION_HEADER_TIMEOUT_MS,
  selectStreamHeaderTimeoutMs,
  TEXT_STREAM_HEADER_TIMEOUT_MS,
} from "./assistant_upstream_timeout.ts";

const MIB = 1024 * 1024;

Deno.test("stream header timeout is text-fast and vision-size-aware", () => {
  assertEquals(
    selectStreamHeaderTimeoutMs(0, 0),
    TEXT_STREAM_HEADER_TIMEOUT_MS,
  );
  assertEquals(selectStreamHeaderTimeoutMs(1, 0), 25_000);
  assertEquals(selectStreamHeaderTimeoutMs(1, MIB), 25_000);
  assertEquals(selectStreamHeaderTimeoutMs(3, MIB + 1), 35_000);
  assertEquals(selectStreamHeaderTimeoutMs(3, 2 * MIB + 1), 45_000);
  assertEquals(
    selectStreamHeaderTimeoutMs(6, 3 * MIB + 1),
    MAX_VISION_HEADER_TIMEOUT_MS,
  );
  assertEquals(selectStreamHeaderTimeoutMs(1, -1), 25_000);
});
