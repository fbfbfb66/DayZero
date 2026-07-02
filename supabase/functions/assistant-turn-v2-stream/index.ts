import "jsr:@supabase/functions-js@2/edge-runtime.d.ts";
import { handler } from "./handler.ts";

Deno.serve(handler);
