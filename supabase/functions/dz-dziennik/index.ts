// eduVULCAN -> task cards. Homework and tests of the next 14 days become one-off cards for the mapped child.
// The server registers itself in eduVULCAN as one more phone (RSA key, like the mobile app); the parent pastes
// the one-time tokens from https://eduvulcan.pl/api/ap, so their password never reaches us.
// Protocol: the unofficial mobile API ("Hebe"), as reverse-engineered by iris, dampud/vulcan and hebece.
// It may stop working whenever VULCAN changes it; errors land in dz_school_links.last_error for the parent to see.
//
// Called by the app (user JWT): status | link {ap} | map {pupil_id, member_id} | sync | unlink
// Called by pg_cron (x-cron-secret): cron
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import { createHash } from "node:crypto";

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

const BASE = "https://lekcjaplus.vulcan.net.pl";
// Identity of the eduVULCAN app; bump when the server starts rejecting it.
const APP = { name: "DzienniczekPlus 3.0", version: "25.09.24 (G)", code: "802", ua: "Dart/3.8 (dart:io)" };
const DEVICE = { os: "Android", model: "SM-A525F" };
const TZ = "Europe/Warsaw";
const DAYS_AHEAD = 14;
const REMIND_AT = "16:00";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...CORS, "content-type": "application/json" } });

type Cred = { certificate: string; private_key: string; fingerprint: string; device_id: string; tenants: string[] };
type Account = { pupil_id: number; name: string; class: string | null; rest_url: string; member_id: string | null };
type Link = { family_id: string; credential: Cred; accounts: Account[]; last_sync_at: string | null; last_error: string | null };

class HebeError extends Error {
  constructor(public code: number, message: string) { super(message); }
}
// Codes after which only a fresh link helps (from iris)
const AUTH_CODES = new Set([100, 102, 103, 154, 201, 202, 203, 204]);
const CODE_PL: Record<number, string> = {
  154: "eduVULCAN nie zna już tego połączenia (np. po zmianie hasła). Połącz dziennik jeszcze raz.",
  201: "Te dane były już użyte. Odśwież stronę eduvulcan.pl/api/ap i skopiuj jeszcze raz.",
  202: "eduVULCAN odrzucił dane. Odśwież stronę eduvulcan.pl/api/ap i skopiuj jeszcze raz.",
  204: "Dane z eduvulcan.pl/api/ap wygasły. Odśwież tę stronę i skopiuj jeszcze raz.",
};
const errText = (e: unknown) =>
  e instanceof HebeError ? (CODE_PL[e.code] ?? `eduVULCAN: ${e.code} ${e.message}`) : (e as Error)?.message || "Nieznany błąd";

// ---------- crypto & transport ----------
const enc = new TextEncoder();
const b64 = (buf: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function newCredential(): Promise<Cred> {
  const kp = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const spki = b64(await crypto.subtle.exportKey("spki", kp.publicKey));
  const pem = "-----BEGIN PUBLIC KEY-----\n" + spki.match(/.{1,64}/g)!.join("\n") + "\n-----END PUBLIC KEY-----\n";
  return {
    certificate: spki,
    private_key: b64(await crypto.subtle.exportKey("pkcs8", kp.privateKey)),
    fingerprint: createHash("md5").update(pem).digest("hex"),
    device_id: crypto.randomUUID(),
    tenants: [],
  };
}

function localParts(d = new Date()) {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })
    .formatToParts(d).map((x) => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}:${p.second}` };
}
const addDays = (day: string, n: number) => { const d = new Date(day + "T12:00:00Z"); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

async function hebe(cred: Cred, method: string, url: string, opts: { query?: Record<string, string | number>; envelope?: unknown; pupilId?: number } = {}) {
  const now = new Date(), lp = localParts(now);
  const body = opts.envelope === undefined ? null : JSON.stringify({
    AppName: APP.name, AppVersion: APP.version, NotificationToken: "", API: 1, RequestId: crypto.randomUUID(),
    Timestamp: Math.floor(now.getTime() / 1000), TimestampFormatted: `${lp.day} ${lp.time}`, Envelope: opts.envelope,
  });
  const m = url.match(/(api\/mobile\/.*)/);
  const headers: Record<string, string> = {
    vOS: DEVICE.os, vVersionCode: APP.code, vAPI: "1",
    vCanonicalUrl: encodeURIComponent(m ? m[1] : url).toLowerCase(),
    vDate: now.toUTCString(),
    "User-Agent": APP.ua,
    "Content-Type": body ? "application/json; charset=utf-8" : "application/json",
  };
  if (opts.pupilId) headers.vHint = String(opts.pupilId);
  const names = body === null ? ["vCanonicalUrl", "vDate"] : ["vCanonicalUrl", "Digest", "vDate"];
  if (body !== null) headers.Digest = b64(await crypto.subtle.digest("SHA-256", enc.encode(body)));
  const key = await crypto.subtle.importKey("pkcs8", unb64(cred.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = b64(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(names.map((n) => headers[n]).join(""))));
  headers.Signature = `keyId="${cred.fingerprint}",headers="${names.join(" ")}",algorithm="sha256withrsa",signature=Base64(SHA256withRSA(${sig}))`;
  if (body !== null) headers.Digest = "SHA-256=" + headers.Digest;

  const qs = opts.query ? "?" + Object.entries(opts.query).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join("&") : "";
  const res = await fetch(url + qs, { method, headers, body: body ?? undefined, signal: AbortSignal.timeout(30000) });
  const text = await res.text();
  if (res.status !== 200) throw new Error(`eduVULCAN odpowiedział HTTP ${res.status}`);
  let data: any;
  try { data = JSON.parse(text); } catch { throw new Error("eduVULCAN zwrócił stronę zamiast danych"); }
  const code = Number(data?.Status?.Code ?? -1);
  if (code !== 0) throw new HebeError(code, data?.Status?.Message ?? "");
  return data.Envelope;
}

// ---------- linking ----------
function jwtPayload(tok: string): any {
  try { const p = tok.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"); return JSON.parse(atob(p + "=".repeat((4 - (p.length % 4)) % 4))); } catch { return null; }
}

// Accepts the #ap JSON, the whole page source, or bare tokens. Pupil tokens are the JWTs with a `tenant` claim.
function parseAp(raw: string) {
  if (/"IsConsentAccepted"\s*:\s*false/.test(raw)) throw new Error("Na koncie eduVULCAN nie są zaakceptowane zgody. Zaakceptuj je na eduvulcan.pl i spróbuj jeszcze raz.");
  const byTenant = new Map<string, string[]>();
  let expired = 0;
  for (const tok of new Set(raw.match(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g) ?? [])) {
    const p = jwtPayload(tok);
    if (!p?.tenant) continue;
    if (p.exp && p.exp * 1000 < Date.now()) { expired++; continue; }
    byTenant.set(p.tenant, [...(byTenant.get(p.tenant) ?? []), tok]);
  }
  if (!byTenant.size) throw new Error(expired
    ? "Dane z eduvulcan.pl/api/ap wygasły. Odśwież tę stronę i skopiuj jeszcze raz."
    : "To nie wygląda na dane z eduvulcan.pl/api/ap. Skopiuj je jeszcze raz według instrukcji.");
  return byTenant;
}

const norm = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();

async function link(familyId: string, ap: string) {
  const byTenant = parseAp(ap);
  const cred = await newCredential();
  const accounts: Account[] = [];
  for (const [tenant, tokens] of byTenant) {
    await hebe(cred, "POST", `${BASE}/${tenant}/api/mobile/register/jwt`, {
      envelope: { OS: DEVICE.os, Certificate: cred.certificate, CertificateType: "RSA_PEM", DeviceModel: DEVICE.model,
        SelfIdentifier: cred.device_id, CertificateThumbprint: cred.fingerprint, Tokens: tokens },
    });
    cred.tenants.push(tenant);
    const list = await hebe(cred, "GET", `${BASE}/${tenant}/api/mobile/register/hebe`, { query: { mode: 2 } });
    for (const a of list ?? []) {
      const id = a?.Pupil?.Id;
      if (!id || accounts.some((x) => x.pupil_id === id)) continue;
      accounts.push({ pupil_id: id, name: [a.Pupil.FirstName, a.Pupil.Surname].filter(Boolean).join(" "), class: a.ClassDisplay ?? null, rest_url: a.Unit?.RestURL, member_id: null });
    }
  }
  if (!accounts.length) throw new Error("Połączyłem się z eduVULCAN, ale na tym koncie nie ma żadnego ucznia.");

  // Keep earlier choices, otherwise match a child member by first name
  const { data: old } = await supabase.from("dz_school_links").select("credential,accounts").eq("family_id", familyId).maybeSingle();
  const { data: members } = await supabase.from("dz_members").select("id,name,role").eq("family_id", familyId);
  for (const a of accounts) {
    const prev = (old?.accounts as Account[] | undefined)?.find((x) => x.pupil_id === a.pupil_id);
    const first = norm(a.name.split(" ")[0] ?? "");
    a.member_id = prev?.member_id ?? (members ?? []).find((m) => m.role === "child" && first && norm(m.name).startsWith(first))?.id ?? null;
  }
  if (old) await unregister(old.credential as Cred);
  await supabase.from("dz_school_links").upsert({ family_id: familyId, credential: cred, accounts, last_sync_at: null, last_error: null });
}

async function unregister(cred: Cred) {
  for (const t of cred.tenants ?? []) { try { await hebe(cred, "DELETE", `${BASE}/${t}/api/mobile/register`); } catch { /* already gone */ } }
}

// ---------- sync ----------
const strip = (s: string) => s.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\n{3,}/g, "\n\n").trim();

async function fetchKind(cred: Cred, a: Account, kind: "homework" | "exam", from: string, to: string) {
  const list = await hebe(cred, "GET", `${a.rest_url.replace(/\/$/, "")}/mobile/${kind}/byPupil`, {
    pupilId: a.pupil_id,
    query: { pupilId: a.pupil_id, dateFrom: from, dateTo: to, lastSyncDate: "1970-01-01 01:00:00", lastId: -2147483648, pageSize: 500 },
  });
  return Array.isArray(list) ? list : [];
}

function toCard(kind: "hw" | "ex", a: Account, x: any) {
  const subject = String(x?.Subject?.Name ?? "").trim() || "szkoła";
  const title = kind === "hw" ? `Zadanie domowe: ${subject}` : `${String(x?.Type ?? "").trim() || "Sprawdzian"}: ${subject}`;
  const details = strip(String(x?.Content ?? "")).slice(0, 600) || null;
  return {
    external_id: `${a.pupil_id}:${kind}:${x?.Id}`,
    title: title.slice(0, 80),
    details,
    due_date: String(x?.Deadline?.Date ?? x?.Date?.Date ?? "").slice(0, 10) || null,
  };
}

async function sync(l: Link) {
  const today = localParts().day, to = addDays(today, DAYS_AHEAD);
  let added = 0, changed = 0, removed = 0;
  try {
    for (const a of l.accounts) {
      if (!a.member_id) continue;
      const [hw, ex] = await Promise.all([fetchKind(l.credential, a, "homework", today, to), fetchKind(l.credential, a, "exam", today, to)]);
      const cards = [...hw.map((x) => toCard("hw", a, x)), ...ex.map((x) => toCard("ex", a, x))]
        .filter((c) => c.due_date && c.due_date >= today && c.due_date <= to);
      const { data: existing } = await supabase.from("dz_tasks").select("id,external_id,title,details,due_date,member_id,status")
        .eq("family_id", l.family_id).eq("source", "eduvulcan").like("external_id", `${a.pupil_id}:%`);
      const byExt = new Map((existing ?? []).map((t) => [t.external_id, t]));
      for (const c of cards) {
        const old = byExt.get(c.external_id);
        if (!old) {
          const { error } = await supabase.from("dz_tasks").insert({ family_id: l.family_id, member_id: a.member_id, ...c, source: "eduvulcan",
            steps: [], repeat: "once", remind_at: REMIND_AT, status: "todo", status_day: today });
          if (!error) added++;
        } else if (old.title !== c.title || old.details !== c.details || old.due_date !== c.due_date || old.member_id !== a.member_id) {
          await supabase.from("dz_tasks").update({ ...c, member_id: a.member_id, updated_at: new Date().toISOString() }).eq("id", old.id);
          changed++;
        }
      }
      // Removed in the dziennik (or moved out of the window): drop cards nobody finished yet
      const keep = new Set(cards.map((c) => c.external_id));
      const gone = (existing ?? []).filter((t) => !keep.has(t.external_id) && t.status !== "done" && t.due_date >= today && t.due_date <= to).map((t) => t.id);
      if (gone.length) { await supabase.from("dz_tasks").delete().in("id", gone); removed += gone.length; }
    }
    await supabase.from("dz_tasks").delete().eq("family_id", l.family_id).eq("source", "eduvulcan").lt("due_date", addDays(today, -30));
    await supabase.from("dz_school_links").update({ last_sync_at: new Date().toISOString(), last_error: null }).eq("family_id", l.family_id);
    return { added, changed, removed };
  } catch (e) {
    await supabase.from("dz_school_links").update({ last_error: errText(e) }).eq("family_id", l.family_id);
    throw e;
  }
}

async function status(familyId: string) {
  const { data } = await supabase.from("dz_school_links").select("accounts,last_sync_at,last_error").eq("family_id", familyId).maybeSingle();
  if (!data) return { linked: false };
  return { linked: true, last_sync_at: data.last_sync_at, last_error: data.last_error,
    accounts: (data.accounts as Account[]).map(({ pupil_id, name, class: cls, member_id }) => ({ pupil_id, name, class: cls, member_id })) };
}

const getLink = async (familyId: string) =>
  (await supabase.from("dz_school_links").select("*").eq("family_id", familyId).maybeSingle()).data as Link | null;

// ---------- entry ----------
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const body = await req.json().catch(() => ({}));

  if (body.action === "cron") {
    const { data: cfg } = await supabase.from("dz_config").select("value").eq("key", "cron_secret").maybeSingle();
    if (!cfg?.value || req.headers.get("x-cron-secret") !== cfg.value) return json({ error: "forbidden" }, 403);
    const { data: links } = await supabase.from("dz_school_links").select("*");
    const out: unknown[] = [];
    for (const l of (links ?? []) as Link[]) {
      try { out.push(await sync(l)); } catch (e) { out.push({ error: errText(e) }); }
    }
    return json({ ok: true, out });
  }

  // App calls: parents only
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: { user } } = await supabase.auth.getUser(token);
  if (!user) return json({ error: "Brak logowania" }, 401);
  const { data: dev } = await supabase.from("dz_devices").select("family_id,member_id").eq("user_id", user.id).maybeSingle();
  const { data: me } = dev?.member_id ? await supabase.from("dz_members").select("role").eq("id", dev.member_id).maybeSingle() : { data: null };
  if (!dev || me?.role !== "parent") return json({ error: "Dziennik może połączyć tylko rodzic" }, 403);
  const fid = dev.family_id as string;

  try {
    if (body.action === "status") return json(await status(fid));
    if (body.action === "link") {
      if (typeof body.ap !== "string" || body.ap.length > 200000) return json({ error: "Wklej dane z eduvulcan.pl/api/ap" }, 400);
      await link(fid, body.ap);
      const l = await getLink(fid);
      let result = null;
      try { result = await sync(l!); } catch { /* shown via last_error */ }
      return json({ ...(await status(fid)), result });
    }
    if (body.action === "map") {
      const l = await getLink(fid);
      if (!l) return json({ error: "Dziennik nie jest połączony" }, 400);
      if (body.member_id) {
        const { data: m } = await supabase.from("dz_members").select("id").eq("id", body.member_id).eq("family_id", fid).maybeSingle();
        if (!m) return json({ error: "Nie ma takiej osoby w rodzinie" }, 400);
      }
      const accounts = l.accounts.map((a) => (a.pupil_id === Number(body.pupil_id) ? { ...a, member_id: body.member_id || null } : a));
      await supabase.from("dz_school_links").update({ accounts }).eq("family_id", fid);
      // Cards follow the child they were moved to; unmapped pupil -> its open cards go away
      const prefix = `${Number(body.pupil_id)}:%`;
      if (body.member_id) await supabase.from("dz_tasks").update({ member_id: body.member_id }).eq("family_id", fid).eq("source", "eduvulcan").like("external_id", prefix);
      else await supabase.from("dz_tasks").delete().eq("family_id", fid).eq("source", "eduvulcan").neq("status", "done").like("external_id", prefix);
      let result = null;
      try { result = await sync({ ...l, accounts }); } catch { /* shown via last_error */ }
      return json({ ...(await status(fid)), result });
    }
    if (body.action === "sync") {
      const l = await getLink(fid);
      if (!l) return json({ error: "Dziennik nie jest połączony" }, 400);
      const result = await sync(l);
      return json({ ...(await status(fid)), result });
    }
    if (body.action === "unlink") {
      const l = await getLink(fid);
      if (l) await unregister(l.credential);
      await supabase.from("dz_school_links").delete().eq("family_id", fid);
      await supabase.from("dz_tasks").delete().eq("family_id", fid).eq("source", "eduvulcan").neq("status", "done");
      return json({ linked: false });
    }
    return json({ error: "Nieznana akcja" }, 400);
  } catch (e) {
    return json({ error: errText(e), relink: e instanceof HebeError && AUTH_CODES.has(e.code) }, 400);
  }
});
