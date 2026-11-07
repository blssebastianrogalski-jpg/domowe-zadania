// Runs every minute (pg_cron -> pg_net). Sends Web Push for:
//  - snoozed tasks whose snooze time has passed,
//  - daily first reminders (remind_at) not yet sent today (only on the task's chosen weekdays, if any),
//  - help requests and finished tasks of children (to parents' devices),
//  - nudges at fixed times (NUDGE_AT): one summary per child of today's unfinished tasks and homework due tomorrow,
//  - ringing alarms (budzik): twice a minute to the child until the challenge is solved, for at most 60 minutes;
//    once to parents if it is still ringing after 15 minutes. A parent's message (komunikat) is a one-day alarm
//    with a text: it rings the same way and its text is the notification's body.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import webpush from "npm:web-push@3.6.7";

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

type Task = {
  id: string; family_id: string; member_id: string; title: string; due: string | null; remind_at: string | null;
  repeat: string; status: string; status_day: string | null; snooze_until: string | null; due_date: string | null; days: number[] | null;
  snooze_notified_at: string | null; remind_notified_day: string | null; help_notified_at: string | null; done_notified_day: string | null;
};

type Alarm = { id: string; family_id: string; member_id: string; at: string; days: number[]; challenge: string; dismissed_day: string | null;
  message?: string | null; ring_on?: string | null; sent_by?: string | null };

function localNow(tz: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(new Date()).map((p) => [p.type, p.value]));
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, hm: `${parts.hour}:${parts.minute}`, dow: new Date(day + "T12:00:00Z").getUTCDay() };
}

// Times (family local) of the summary nudge: every 30 minutes from 15:00 to 20:30;
// dz_config key nudge_times overrides, e.g. "16:00,18:00,19:30"
const NUDGE_AT = Array.from({ length: 12 }, (_, i) => `${String(15 + Math.floor(i / 2)).padStart(2, "0")}:${i % 2 ? "30" : "00"}`);
const nextDay = (day: string) => { const d = new Date(day + "T12:00:00Z"); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10); };

// Repeating task limited to chosen weekdays (0 = Sunday): no reminders on the other days
const offDay = (t: Task, dow: number) => t.repeat === "daily" && Array.isArray(t.days) && t.days.length > 0 && !t.days.includes(dow);

function effectiveStatus(t: Task, day: string) {
  if (t.repeat === "daily" && t.status_day !== day) return "todo";
  return t.status;
}

Deno.serve(async (req) => {
  const { data: cfg } = await supabase.from("dz_config").select("key,value");
  const conf: Record<string, string> = Object.fromEntries((cfg ?? []).map((r) => [r.key, r.value]));
  if (!conf.cron_secret || req.headers.get("x-cron-secret") !== conf.cron_secret) return new Response("forbidden", { status: 403 });
  // First run: create the VAPID key pair server-side so the private key never leaves the database
  if (!conf.vapid_private || !conf.vapid_public) {
    const keys = webpush.generateVAPIDKeys();
    await supabase.from("dz_config").upsert([{ key: "vapid_public", value: keys.publicKey }, { key: "vapid_private", value: keys.privateKey }]);
    conf.vapid_public = keys.publicKey; conf.vapid_private = keys.privateKey;
  }
  conf.vapid_subject ??= "https://blssebastianrogalski-jpg.github.io/domowe-zadania/";
  webpush.setVapidDetails(conf.vapid_subject, conf.vapid_public, conf.vapid_private);

  const { data: families } = await supabase.from("dz_families").select("id,tz");
  const { data: tasks } = await supabase.from("dz_tasks").select("*");
  const { data: devices } = await supabase.from("dz_devices").select("user_id,family_id,member_id");
  const { data: members } = await supabase.from("dz_members").select("id,name,role,family_id");
  const { data: subs } = await supabase.from("dz_push_subs").select("*");
  const { data: alarms } = await supabase.from("dz_alarms").select("*").eq("enabled", true);
  const tzOf = new Map((families ?? []).map((f) => [f.id, f.tz]));
  const memberOf = new Map((members ?? []).map((m) => [m.id, m]));

  const subsFor = (pred: (d: { member_id: string | null }) => boolean, familyId: string) => {
    const users = new Set((devices ?? []).filter((d) => d.family_id === familyId && pred(d)).map((d) => d.user_id));
    return (subs ?? []).filter((s) => users.has(s.user_id));
  };

  let sent = 0;
  async function push(targets: any[], payload: Record<string, unknown>, ttl = 600) {
    for (const s of targets) {
      try {
        await webpush.sendNotification({ endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } }, JSON.stringify(payload), { TTL: ttl, urgency: "high" });
        sent++;
      } catch (e: any) {
        if (e?.statusCode === 404 || e?.statusCode === 410) await supabase.from("dz_push_subs").delete().eq("endpoint", s.endpoint);
      }
    }
  }

  const now = Date.now();
  for (const t of (tasks ?? []) as Task[]) {
    const { day, hm, dow } = localNow(tzOf.get(t.family_id) ?? "Europe/Warsaw");
    const st = effectiveStatus(t, day);
    const forMember = () => subsFor((d) => d.member_id === t.member_id, t.family_id);

    if (st === "snoozed" && t.snooze_until && Date.parse(t.snooze_until) <= now &&
        (!t.snooze_notified_at || Date.parse(t.snooze_notified_at) < Date.parse(t.snooze_until))) {
      await push(forMember(), { title: "Wracamy do zadania", body: t.title + (t.due ? ` · termin przed ${t.due.slice(0, 5)}` : ""), task: t.id, kind: "snooze" });
      await supabase.from("dz_tasks").update({ snooze_notified_at: new Date().toISOString() }).eq("id", t.id);
    }

    if (t.remind_at && st === "todo" && !offDay(t, dow) && t.remind_notified_day !== day && t.remind_at.slice(0, 5) <= hm &&
        (t.repeat === "daily" || !t.status_day || t.status_day <= day)) {
      await push(forMember(), { title: "Czas na: " + t.title, body: t.due ? `Termin przed ${t.due.slice(0, 5)}. Możesz odłożyć.` : "Możesz zrobić teraz albo odłożyć.", task: t.id, kind: "remind" });
      await supabase.from("dz_tasks").update({ remind_notified_day: day }).eq("id", t.id);
    }

    if (st === "help" && !t.help_notified_at) {
      const parents = subsFor((d) => d.member_id !== null && memberOf.get(d.member_id!)?.role === "parent", t.family_id);
      const who = memberOf.get(t.member_id)?.name ?? "Ktoś";
      await push(parents, { title: `${who} prosi o pomoc`, body: t.title, task: t.id, kind: "help" });
      await supabase.from("dz_tasks").update({ help_notified_at: new Date().toISOString() }).eq("id", t.id);
    }

    // A child finished a task: tell the parents once per completion day
    if (t.status === "done" && t.status_day && t.done_notified_day !== t.status_day && memberOf.get(t.member_id)?.role === "child") {
      const parents = subsFor((d) => d.member_id !== null && memberOf.get(d.member_id!)?.role === "parent", t.family_id);
      const who = memberOf.get(t.member_id)?.name ?? "Dziecko";
      await push(parents, { title: `✅ ${who}: zrobione`, body: t.title, task: t.id, kind: "help" });
      await supabase.from("dz_tasks").update({ done_notified_day: t.status_day }).eq("id", t.id);
    }
  }
  // Nudges: the cron runs every minute, so a nudge fires in the minute matching one of the times
  const nudgeAt = conf.nudge_times ? conf.nudge_times.split(",").map((x) => x.trim()) : NUDGE_AT;
  for (const m of members ?? []) {
    if (m.role !== "child") continue;
    const { day, hm, dow } = localNow(tzOf.get(m.family_id) ?? "Europe/Warsaw");
    if (!nudgeAt.includes(hm)) continue;
    const tomorrow = nextDay(day);
    const mine = ((tasks ?? []) as Task[]).filter((t) => t.member_id === m.id);
    // Today: not done and not snoozed into the future (snoozing stays without penalty); school cards count
    // only for the day before their date, a card dated today was due at school already
    const today = mine.filter((t) => {
      const st = effectiveStatus(t, day);
      if (st === "done" || (st === "snoozed" && t.snooze_until && Date.parse(t.snooze_until) > now)) return false;
      if (t.due_date || offDay(t, dow)) return false;
      return t.repeat === "daily" || !t.status_day || t.status_day <= day;
    });
    const forTomorrow = mine.filter((t) => t.due_date === tomorrow && effectiveStatus(t, day) !== "done");
    if (!today.length && !forTomorrow.length) continue;
    const list = (ts: Task[]) => ts.slice(0, 4).map((t) => t.title).join(", ") + (ts.length > 4 ? ` i ${ts.length - 4} więcej` : "");
    const n = today.length;
    const title = n ? `📋 ${m.name}, na dziś zostało: ${n} ${n === 1 ? "zadanie" : n < 5 ? "zadania" : "zadań"}` : `📚 ${m.name}, na jutro do szkoły`;
    const body = [n ? list(today) : "", forTomorrow.length ? "Na jutro: " + list(forTomorrow) : ""].filter(Boolean).join("\n");
    await push(subsFor((d) => d.member_id === m.id, m.family_id), { title, body, task: "nudge-" + m.id, kind: "nudge", count: n }, 3600);
  }

  // Alarms: minutes since the set time, today's local weekday, not yet dismissed today
  const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
  const rings = new Map<string, { targets: any[]; payload: Record<string, unknown>; day: string }>();
  for (const a of (alarms ?? []) as Alarm[]) {
    const { day, hm, dow } = localNow(tzOf.get(a.family_id) ?? "Europe/Warsaw");
    const since = toMin(hm) - toMin(a.at);
    if (!a.days.includes(dow) || (a.ring_on && a.ring_on !== day) || since < 0 || since >= 60 || a.dismissed_day === day) continue;
    const who = memberOf.get(a.member_id)?.name ?? "";
    const solve = (a.challenge === "code" ? "przepisz kod" : "rozwiąż zadania") + ", żeby wyłączyć alarm.";
    const from = a.sent_by ? memberOf.get(a.sent_by)?.name : "";
    const ring = { targets: subsFor((d) => d.member_id === a.member_id, a.family_id), payload: a.message
      ? { title: from ? "📢 " + from + " pisze:" : "📢 Wiadomość od rodzica", body: a.message + "\n\nOtwórz aplikację i " + solve, task: "alarm-" + a.id, kind: "alarm" }
      : { title: "⏰ Pobudka" + (who ? ", " + who : "") + "!", body: "Budzik dzwoni od " + a.at.slice(0, 5) + ". Otwórz aplikację i rozwiąż " + (a.challenge === "code" ? "kod" : "zadania") + ", żeby go wyłączyć.", task: "alarm-" + a.id, kind: "alarm" } };
    await push(ring.targets, ring.payload, 30);
    rings.set(a.id, { ...ring, day });
    if (since >= 15) {
      const { data: ring } = await supabase.from("dz_alarm_rings").select("parent_notified_at").eq("alarm_id", a.id).eq("day", day).maybeSingle();
      if (!ring?.parent_notified_at) {
        const parents = subsFor((d) => d.member_id !== null && memberOf.get(d.member_id!)?.role === "parent", a.family_id);
        await push(parents, a.message
          ? { title: `${who || "Dziecko"} jeszcze nie wyłączył(a) komunikatu`, body: "„" + a.message + "” dzwoni od " + a.at.slice(0, 5) + ".", task: "alarm-" + a.id, kind: "help" }
          : { title: `${who || "Dziecko"} jeszcze nie wyłączył(a) budzika`, body: "Dzwoni od " + a.at.slice(0, 5) + ".", task: "alarm-" + a.id, kind: "help" });
        await supabase.from("dz_alarm_rings").upsert({ alarm_id: a.id, day, parent_notified_at: new Date().toISOString() });
      }
    }
  }
  // Cron runs once a minute: ring once more half a minute later, unless the child turned the alarm off meanwhile
  if (rings.size) {
    const again = (async () => {
      await new Promise((r) => setTimeout(r, 30_000));
      const { data: still } = await supabase.from("dz_alarms").select("id,dismissed_day,enabled").in("id", [...rings.keys()]);
      for (const a of still ?? []) {
        const r = rings.get(a.id)!;
        if (a.enabled && a.dismissed_day !== r.day) await push(r.targets, r.payload, 30);
      }
    })();
    // @ts-ignore EdgeRuntime is the Supabase edge runtime global
    if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(again); else await again;
  }
  return new Response(JSON.stringify({ ok: true, sent }), { headers: { "content-type": "application/json" } });
});
