// Runs every minute (pg_cron -> pg_net). Sends Web Push for:
//  - snoozed tasks whose snooze time has passed,
//  - daily first reminders (remind_at) not yet sent today,
//  - help requests (to parents' devices),
//  - ringing alarms (budzik): every minute to the child until the challenge is solved, for at most 60 minutes;
//    once to parents if it is still ringing after 15 minutes.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import webpush from "npm:web-push@3.6.7";

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

type Task = {
  id: string; family_id: string; member_id: string; title: string; due: string | null; remind_at: string | null;
  repeat: string; status: string; status_day: string | null; snooze_until: string | null;
  snooze_notified_at: string | null; remind_notified_day: string | null; help_notified_at: string | null;
};

type Alarm = { id: string; family_id: string; member_id: string; at: string; days: number[]; challenge: string; dismissed_day: string | null };

function localNow(tz: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(new Date()).map((p) => [p.type, p.value]));
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, hm: `${parts.hour}:${parts.minute}`, dow: new Date(day + "T12:00:00Z").getUTCDay() };
}

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
    const { day, hm } = localNow(tzOf.get(t.family_id) ?? "Europe/Warsaw");
    const st = effectiveStatus(t, day);
    const forMember = () => subsFor((d) => d.member_id === t.member_id, t.family_id);

    if (st === "snoozed" && t.snooze_until && Date.parse(t.snooze_until) <= now &&
        (!t.snooze_notified_at || Date.parse(t.snooze_notified_at) < Date.parse(t.snooze_until))) {
      await push(forMember(), { title: "Wracamy do zadania", body: t.title + (t.due ? ` · termin przed ${t.due.slice(0, 5)}` : ""), task: t.id, kind: "snooze" });
      await supabase.from("dz_tasks").update({ snooze_notified_at: new Date().toISOString() }).eq("id", t.id);
    }

    if (t.remind_at && st === "todo" && t.remind_notified_day !== day && t.remind_at.slice(0, 5) <= hm &&
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
  }
  // Alarms: minutes since the set time, today's local weekday, not yet dismissed today
  const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
  for (const a of (alarms ?? []) as Alarm[]) {
    const { day, hm, dow } = localNow(tzOf.get(a.family_id) ?? "Europe/Warsaw");
    const since = toMin(hm) - toMin(a.at);
    if (!a.days.includes(dow) || since < 0 || since >= 60 || a.dismissed_day === day) continue;
    const who = memberOf.get(a.member_id)?.name ?? "";
    await push(subsFor((d) => d.member_id === a.member_id, a.family_id),
      { title: "⏰ Pobudka" + (who ? ", " + who : "") + "!", body: "Budzik dzwoni od " + a.at.slice(0, 5) + ". Otwórz aplikację i rozwiąż " + (a.challenge === "code" ? "kod" : "zadania") + ", żeby go wyłączyć.", task: "alarm-" + a.id, kind: "alarm" }, 60);
    if (since >= 15) {
      const { data: ring } = await supabase.from("dz_alarm_rings").select("parent_notified_at").eq("alarm_id", a.id).eq("day", day).maybeSingle();
      if (!ring?.parent_notified_at) {
        const parents = subsFor((d) => d.member_id !== null && memberOf.get(d.member_id!)?.role === "parent", a.family_id);
        await push(parents, { title: `${who || "Dziecko"} jeszcze nie wyłączył(a) budzika`, body: "Dzwoni od " + a.at.slice(0, 5) + ".", task: "alarm-" + a.id, kind: "help" });
        await supabase.from("dz_alarm_rings").upsert({ alarm_id: a.id, day, parent_notified_at: new Date().toISOString() });
      }
    }
  }
  return new Response(JSON.stringify({ ok: true, sent }), { headers: { "content-type": "application/json" } });
});
