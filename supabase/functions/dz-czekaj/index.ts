// Long poll for the Android app (Budzik DZ). The app sends its device secret (dz_alarm_links) and the last change
// it knows ("since"). The function answers as soon as any of the child's alarms or messages changes (a new
// message from a parent, an edited alarm) or a new notification lands in its inbox (dz_native_inbox, migration 011:
// task reminders, nudges, a parent's "help"/"done" news), or after about 50 seconds with no change. The app then asks again,
// so a parent's message rings on the phone within a few seconds. Deployed with JWT verification off:
// it checks the device secret itself and returns only a timestamp, nothing about the family.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
const WAIT_MS = 50_000, STEP_MS = 1_000;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

async function latest(member: string): Promise<string> {
  const [{ data }, { data: inbox }] = await Promise.all([
    supabase.from("dz_alarms").select("updated_at").eq("member_id", member).order("updated_at", { ascending: false }).limit(1).maybeSingle(),
    // Before migration 011 this table does not exist: the error leaves inbox null and only alarms count
    supabase.from("dz_native_inbox").select("created_at").eq("member_id", member).order("id", { ascending: false }).limit(1).maybeSingle(),
  ]);
  // A member without any alarm or notification yet: a fixed start, so the app waits instead of asking again at once
  const times = [data?.updated_at, inbox?.created_at].filter(Boolean).map((t) => Date.parse(t as string));
  return times.length ? new Date(Math.max(...times)).toISOString() : "1970-01-01T00:00:00Z";
}

Deno.serve(async (req) => {
  let body: { secret?: string; since?: string } = {};
  try { body = await req.json(); } catch { /* empty */ }
  if (typeof body.secret !== "string" || body.secret.length < 32) return json({ error: "secret" }, 400);
  const { data: link } = await supabase.from("dz_alarm_links").select("member_id").eq("secret", body.secret).maybeSingle();
  if (!link) return json({ unlinked: true }, 403);

  const since = typeof body.since === "string" ? body.since : "";
  const start = Date.now();
  let at = await latest(link.member_id);
  // First call (no "since"): just tell the app where we are
  if (!since) return json({ changed: false, at });
  while (Date.parse(at) <= Date.parse(since) && Date.now() - start < WAIT_MS) {
    await new Promise((r) => setTimeout(r, STEP_MS));
    at = await latest(link.member_id);
  }
  return json({ changed: Date.parse(at) > Date.parse(since), at });
});
