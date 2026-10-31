import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.4/+esm";

const CFG = window.DZ_CONFIG;
const sb = createClient(CFG.supabaseUrl, CFG.supabaseAnonKey, { auth: { persistSession: true, autoRefreshToken: true } });
const $ = (id) => document.getElementById(id);
const S = { uid: null, device: null, family: null, members: [], tasks: [], tally: {}, alarms: [], me: null, isParent: false, school: null };

// ---------- small helpers ----------
const pad = (n) => String(n).padStart(2, "0");
const dayOf = (d) => d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
const today = () => dayOf(new Date());
const hm = (ms) => { const d = new Date(ms); return pad(d.getHours()) + ":" + pad(d.getMinutes()); };
const t5 = (t) => (t ? String(t).slice(0, 5) : null);
const dueMs = (t) => { if (!t.due) return null; const [h, m] = t5(t.due).split(":").map(Number); const d = new Date(); d.setHours(h, m, 0, 0); return d.getTime(); };
function st(t) { if (t.repeat === "daily" && t.status_day !== today()) return "todo"; return t.status || "todo"; }
const LABEL = { todo: "Do zrobienia", snoozed: "Do zrobienia", doing: "W trakcie", help: "Potrzebuje pomocy", done: "Zrobione" };
const CLS = { todo: "s-todo", snoozed: "s-todo", doing: "s-doing", help: "s-help", done: "s-done" };
const addDays = (day, n) => { const d = new Date(day + "T12:00:00"); d.setDate(d.getDate() + n); return dayOf(d); };
// Cards from the school dziennik carry a date: the parent sees them until that day, the child from 3 days before
function visible(t, forChild = false) {
  if (t.due_date && t.due_date < today()) return false;
  if (forChild && t.due_date && today() < addDays(t.due_date, -3)) return false;
  return t.repeat === "daily" || st(t) !== "done" || t.status_day === today();
}
const WD = ["niedziela", "poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota"];
function dayLabel(day) {
  if (day === today()) return "dziś";
  if (day === addDays(today(), 1)) return "jutro";
  const d = new Date(day + "T12:00:00");
  return WD[d.getDay()] + " " + pad(d.getDate()) + "." + pad(d.getMonth() + 1);
}
const sortKey = (t) => (t.due_date || today()) + (t5(t.due) || "99");
const memberName = (id) => (S.members.find((m) => m.id === id) || {}).name || "Ktoś";
// "Wszystkie dzieci" w liście „Dla kogo”: jedno wpisanie tworzy osobną kartę dla każdego dziecka.
const ALL_KIDS = "__all_kids";
const childIds = () => S.members.filter((m) => m.role === "child").map((m) => m.id);
function namesList(ids) {
  const n = ids.map(memberName);
  return n.length > 1 ? n.slice(0, -1).join(", ") + " i " + n[n.length - 1] : n.join("");
}
const kidsLabel = (ids) => ids.length <= 3 ? namesList(ids) + " razem" : "Wszystkie dzieci (" + ids.length + ")";

function toast(msg) { const el = $("toast"); el.textContent = msg; el.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => (el.hidden = true), 4500); }
function el(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids) e.append(c);
  return e;
}
function status(msg) { $("status").hidden = !msg; $("status").textContent = msg || ""; }
const errMsg = (e) => (e && e.message) || "Coś poszło nie tak.";

// ---------- data ----------
async function loadAll() {
  const { data: dev } = await sb.from("dz_devices").select("*").eq("user_id", S.uid).maybeSingle();
  S.device = dev;
  if (!dev) { S.family = null; return; }
  const fid = dev.family_id;
  const since = new Date(); since.setDate(since.getDate() - 7);
  const [fam, mem, tasks, tally, alarms] = await Promise.all([
    sb.from("dz_families").select("*").eq("id", fid).single(),
    sb.from("dz_members").select("*").eq("family_id", fid).order("created_at"),
    sb.from("dz_tasks").select("*").eq("family_id", fid),
    sb.from("dz_tally").select("*").eq("family_id", fid).gte("day", dayOf(since)),
    sb.from("dz_alarms").select("*").eq("family_id", fid).order("at"),
  ]);
  // Before the 004 migration runs the table is missing: the app keeps working without alarms
  S.alarms = alarms.data || [];
  S.family = fam.data; S.members = mem.data || []; S.tasks = tasks.data || [];
  S.tally = Object.fromEntries((tally.data || []).map((r) => [r.day, r.count]));
  S.me = S.members.find((m) => m.id === dev.member_id) || null;
  S.isParent = S.me?.role === "parent";
}

let channel = null;
function subscribe() {
  if (channel || !S.device) return;
  const fid = S.device.family_id;
  const reload = debounce(async () => { await loadAll(); render(); }, 300);
  channel = sb.channel("family-" + fid);
  for (const table of ["dz_tasks", "dz_tally", "dz_members", "dz_alarms"]) channel.on("postgres_changes", { event: "*", schema: "public", table, filter: "family_id=eq." + fid }, reload);
  channel.subscribe();
  document.addEventListener("visibilitychange", () => { if (!document.hidden) reload(); });
  setInterval(() => { if (!document.hidden) reload(); }, 60000);
}
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

async function act(task, action, snooze = null, steps = null) {
  const { error } = await sb.rpc("dz_task_action", { p_task: task.id, p_action: action, p_snooze: snooze ? new Date(snooze).toISOString() : null, p_steps: steps });
  if (error) { toast(errMsg(error)); return false; }
  Object.assign(task, action === "steps" ? { steps_done: steps, steps_day: today() } : { status: action, status_day: today(), snooze_until: snooze ? new Date(snooze).toISOString() : null });
  render();
  return true;
}

// ---------- routing ----------
function view() {
  if (!S.device) return "start";
  if (!S.me) return "pick";
  const h = location.hash.slice(1);
  if (h === "rodzic" && S.isParent) return "rodzic";
  if (h === "ustawienia") return "ustawienia";
  return "moje";
}
addEventListener("hashchange", () => render());

function render() {
  const v = view();
  applySkin();
  for (const name of ["start", "pick", "moje", "rodzic", "ustawienia"]) $("view-" + name).hidden = v !== name;
  $("nav").hidden = !(S.device && S.me);
  $("nav-rodzic").hidden = !S.isParent;
  for (const name of ["moje", "rodzic", "ustawienia"]) { const a = $("nav-" + name); if (v === name) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current"); }
  $("family-line").textContent = S.family ? S.family.name + (S.me ? " · " + S.me.name : "") : "Odłożenie zadania to nie porażka.";
  if (v === "pick") renderPick();
  if (v === "moje") renderMoje();
  if (v === "rodzic") renderRodzic();
  if (v === "ustawienia") renderSettings();
  checkAlarm();
}
setInterval(() => { if (!document.hidden && view() === "moje") render(); }, 20000);

// ---------- onboarding ----------
$("join-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const { error } = await sb.rpc("dz_join_family", { p_code: $("join-code").value });
  if (error) { toast(errMsg(error)); return; }
  await loadAll(); subscribe(); render();
});
$("create-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const { error } = await sb.rpc("dz_create_family", { p_family_name: $("c-family").value, p_parent_name: $("c-name").value });
  if (error) { toast(errMsg(error)); return; }
  await loadAll(); subscribe(); location.hash = "#rodzic"; render();
  toast("Rodzina założona. Dodaj domowników i pierwsze zadanie.");
});

function renderPick() {
  const box = $("pick"); box.replaceChildren();
  for (const m of S.members) box.append(el("button", { class: "chip", onclick: async () => {
    const { error } = await sb.rpc("dz_pick_member", { p_member: m.id });
    if (error) { toast(errMsg(error)); return; }
    await loadAll(); location.hash = m.role === "parent" ? "#rodzic" : "#moje"; render();
  } }, m.name));
}

// ---------- collapsible sections: school (from the dziennik) and home chores ----------
// A closed section shows "! N" when something in it is still to do (snoozed counts too, without any penalty).
const isSchool = (t) => t.source === "eduvulcan";
const openSects = (() => { try { return JSON.parse(localStorage.getItem("dz-sections") || "{}"); } catch { return {}; } })();
function section(key, title, tasks, rowFn, emptyText) {
  const left = tasks.filter((t) => st(t) !== "done").length;
  const d = el("details", { class: "panel sect" });
  d.open = !!openSects[key];
  d.addEventListener("toggle", () => { openSects[key] = d.open; try { localStorage.setItem("dz-sections", JSON.stringify(openSects)); } catch {} });
  d.append(el("summary", {},
    el("span", { class: "sect-title" }, title),
    left ? el("span", { class: "badge", title: left + " do zrobienia" }, "! " + left) : "",
    el("span", { class: "sect-sum" }, left ? left + " do zrobienia" : tasks.length ? "wszystko zrobione" : "")));
  const ul = el("ul", { class: "rows" });
  if (!tasks.length) ul.append(el("li", { class: "empty" }, emptyText));
  for (const t of tasks) ul.append(rowFn(t));
  d.append(ul);
  return d;
}

// ---------- executor ----------
function renderMoje() {
  const mine = S.tasks.filter((t) => t.member_id === S.me.id && visible(t, true));
  const now = Date.now();
  const snoozeAt = (t) => (t.snooze_until ? Date.parse(t.snooze_until) : 0);
  const due = mine.filter((t) => st(t) === "snoozed" && snoozeAt(t) <= now);
  const active = mine.filter((t) => ["doing", "help"].includes(st(t)))
    .concat(due)
    .concat(mine.filter((t) => st(t) === "todo").sort((a, b) => sortKey(a).localeCompare(sortKey(b))));
  const later = mine.filter((t) => st(t) === "snoozed" && snoozeAt(t) > now).sort((a, b) => snoozeAt(a) - snoozeAt(b));

  const hint = $("notif-hint");
  const perm = "Notification" in window ? Notification.permission : "unsupported";
  hint.hidden = perm === "granted";
  if (!hint.hidden) { hint.replaceChildren("Przypomnienia na zablokowanym telefonie są wyłączone. ", el("a", { href: "#ustawienia" }, "Włącz w ustawieniach")); }

  const fw = $("focus-wrap"); fw.replaceChildren();
  if (!active.length) fw.append(el("div", { class: "panel" }, el("h2", {}, later.length ? "Na teraz nic. Odłożone zadania wrócą same." : "Na dziś wszystko zrobione."), el("p", { class: "muted" }, later.length ? "Zobacz listę „Odłożone” poniżej." : "Dobra robota.")));
  else fw.append(focusCard(active[0], active.length - 1));

  const lp = $("later"); lp.replaceChildren(); $("later-panel").hidden = !later.length;
  for (const t of later) lp.append(el("li", { class: "row" }, el("div", { class: "t" }, el("div", {}, t.title), el("div", { class: "meta" }, "Wrócę o " + hm(snoozeAt(t)) + (t.due ? " · termin przed " + t5(t.due) : ""))), el("div", { class: "acts" }, el("button", { onclick: () => act(t, "doing") }, "Robię teraz"))));

  const myRow = (t) => {
    const s = st(t);
    const meta = [t.due_date ? dayLabel(t.due_date) : null, t.due ? "przed " + t5(t.due) : null, s === "snoozed" && snoozeAt(t) > now ? "wrócę o " + hm(snoozeAt(t)) : null].filter(Boolean).join(" · ");
    return el("li", { class: "row" },
      el("div", { class: "t" }, el("div", {}, t.title), meta ? el("div", { class: "meta" }, meta) : ""),
      el("span", { class: "pill " + CLS[s] }, LABEL[s]),
      s === "done" || s === "doing" ? "" : el("div", { class: "acts" }, el("button", { onclick: () => act(t, "doing") }, "Robię teraz")));
  };
  const bySort = (a, b) => (st(a) === "done") - (st(b) === "done") || sortKey(a).localeCompare(sortKey(b));
  $("my-sections").replaceChildren(
    section("moje-szkola", "📚 Zadania szkolne", mine.filter(isSchool).sort(bySort), myRow, "Brak zadań szkolnych."),
    section("moje-dom", "🏠 Zadania domowe", mine.filter((t) => !isSchool(t)).sort(bySort), myRow, "Brak obowiązków domowych."));
  document.title = due.length ? "⏰ " + due[0].title : "Domowe Zadania";
  renderWeek();
  const mineA = S.alarms.filter((a) => a.member_id === S.me.id && a.enabled);
  $("my-alarms-panel").hidden = !mineA.length;
  $("my-alarms").replaceChildren(...mineA.map((a) => el("li", { class: "row" }, el("div", { class: "t" }, el("div", { class: "al-row-time" }, t5(a.at)), el("div", { class: "meta" }, daysLabel(a.days) + " · " + kindLabel(a))))));
  $("my-alarm-acts").replaceChildren(
    el("button", { class: "primary", onclick: nightOn }, "🌙 Tryb nocny"),
    el("button", { onclick: testSound }, "🔊 Sprawdź głośność"));
}

function focusCard(t, more) {
  const s = st(t), now = Date.now(), dm = dueMs(t);
  const card = el("article", { class: "focus", "aria-label": "Zadanie na teraz" });
  card.append(el("div", {}, el("span", { class: "pill " + CLS[s] }, s === "snoozed" ? "Wróciło przypomnienie" : LABEL[s])));
  card.append(el("div", { class: "title" }, t.title));
  if (t.due) card.append(el("div", { class: "when" }, "Termin: przed " + t5(t.due)));
  if (t.due_date) card.append(el("div", { class: "when" }, (t.title.startsWith("Zadanie domowe") ? "Na " : "Kiedy: ") + dayLabel(t.due_date) + (t.source === "eduvulcan" ? " · z dziennika" : "")));
  if (t.details) card.append(el("div", { class: "details" }, t.details));
  if (dm && dm - now <= 15 * 60000 && dm > now) card.append(el("div", { class: "soon" }, "Zostało " + Math.max(1, Math.round((dm - now) / 60000)) + " min. Robisz teraz czy potrzebujesz pomocy?"));
  const steps = Array.isArray(t.steps) ? t.steps : [];
  if (steps.length) {
    const ul = el("ul", { class: "steps" });
    const doneSteps = t.steps_day === today() && Array.isArray(t.steps_done) ? t.steps_done : [];
    steps.forEach((step, i) => {
      const cb = el("input", { type: "checkbox", id: "step-" + t.id + "-" + i });
      cb.checked = doneSteps.includes(i);
      cb.addEventListener("change", () => { const set = new Set(doneSteps); cb.checked ? set.add(i) : set.delete(i); act(t, "steps", null, [...set]); });
      ul.append(el("li", {}, el("label", { for: cb.id }, cb, el("span", {}, step))));
    });
    card.append(ul);
  }
  if (s === "help") card.append(el("p", { class: "soon" }, "Wysłano prośbę o pomoc. Ktoś zaraz przyjdzie."));

  const acts = el("div", { class: "actions" });
  acts.append(el("button", { class: "primary", onclick: async () => { if (await act(t, "done")) cheer(); } }, "Zrobione"));
  acts.append(el("button", { disabled: s === "doing", onclick: async () => { if (await act(t, "doing")) toast("OK, zadanie zostaje na ekranie."); } }, "Robię teraz"));
  const help = el("button", { "aria-expanded": "false" }, "Potrzebuję pomocy");
  acts.append(help);
  const later = el("button", { "aria-expanded": "false" }, "Przypomnij później");
  acts.append(later);
  card.append(acts);

  // Help: the child chooses between calling a parent and the AI helper that guides with questions
  const helpBox = el("div", { class: "helpbox", hidden: true },
    el("button", { class: "primary", disabled: s === "help", onclick: async () => { if (await act(t, "help")) toast("Dałem znać rodzicowi, że potrzebujesz pomocy."); } }, s === "help" ? "Rodzic już wie" : "🙋 Pomoc rodzica"),
    el("button", { onclick: () => openHelper(t) }, "🤖 Pomocnik AI"));
  help.onclick = () => { helpBox.hidden = !helpBox.hidden; help.setAttribute("aria-expanded", String(!helpBox.hidden)); };
  card.append(helpBox);

  const box = el("div", { class: "snooze", hidden: true });
  const snooze = async (ms) => { if (await act(t, "snoozed", ms)) toast("OK, wrócę do Ciebie o " + hm(ms) + "." + (t.due ? " Termin dalej: przed " + t5(t.due) + "." : "")); };
  for (const m of [15, 30]) box.append(el("button", { onclick: () => snooze(Date.now() + m * 60000) }, "za " + m + " min"));
  const at = el("input", { type: "time", id: "snooze-at", "aria-label": "O wybranej godzinie" });
  box.append(at, el("button", { onclick: () => { if (!at.value) { at.focus(); return; } const [h, mi] = at.value.split(":").map(Number); const d = new Date(); d.setHours(h, mi, 0, 0); if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1); snooze(d.getTime()); } }, "Ustaw"));
  later.onclick = () => { box.hidden = !box.hidden; later.setAttribute("aria-expanded", String(!box.hidden)); };
  card.append(box);
  if (more > 0) card.append(el("p", { class: "muted small" }, "Potem jeszcze " + more + (more === 1 ? " zadanie" : more < 5 ? " zadania" : " zadań") + ". Jedno naraz."));
  return card;
}

// ---------- AI helper (dz-pomocnik): asks guiding questions, never gives the answer ----------
const chats = {};
let helperTask = null;
function openHelper(t) {
  helperTask = t;
  chats[t.id] ??= [];
  $("ai-title").textContent = t.title;
  $("ai").hidden = false;
  renderChat();
  if (!chats[t.id].length) askHelper();
  else $("ai-input").focus();
}
function renderChat(busy = false) {
  const box = $("ai-log"); box.replaceChildren();
  for (const m of chats[helperTask.id]) box.append(el("p", { class: "msg " + (m.role === "user" ? "me" : "bot") }, m.content));
  if (busy) box.append(el("p", { class: "msg bot typing" }, "Pomocnik myśli…"));
  box.scrollTop = box.scrollHeight;
  $("ai-send").disabled = busy;
}
async function askHelper() {
  const t = helperTask, log = chats[t.id];
  renderChat(true);
  const { data, error } = await sb.functions.invoke("dz-pomocnik", { body: { task_id: t.id, messages: log } });
  let msg = data?.reply;
  if (error) { msg = null; try { const j = await error.context.json(); msg = j?.error; } catch {} msg = "⚠️ " + (msg || "Pomocnik chwilowo nie odpowiada. Spróbuj za chwilę albo poproś rodzica."); }
  if (helperTask !== t) return;
  if (error) { renderChat(); $("ai-log").append(el("p", { class: "msg err" }, msg)); return; }
  log.push({ role: "assistant", content: msg });
  renderChat();
  $("ai-input").focus();
}
$("ai-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const text = $("ai-input").value.trim();
  if (!text || !helperTask) return;
  $("ai-input").value = "";
  chats[helperTask.id].push({ role: "user", content: text.slice(0, 800) });
  askHelper();
});
$("ai-close").onclick = () => { $("ai").hidden = true; helperTask = null; };
$("ai-parent").onclick = async () => { const t = helperTask; if (t && (await act(t, "help"))) { toast("Dałem znać rodzicowi, że potrzebujesz pomocy."); $("ai").hidden = true; helperTask = null; } };

// Reward after "Zrobione": confetti, a random praise and today's star count; tap or wait to close
const PRAISE = ["Super robota!", "Brawo, jedno z głowy!", "Mistrz zadań!", "Tak trzymać!", "Ekstra, zrobione!", "Jesteś niesamowity/a!"];
function cheer() {
  const box = $("cheer");
  const stars = S.me ? S.tasks.filter((x) => x.member_id === S.me.id && st(x) === "done" && x.status_day === today()).length : 1;
  const bits = el("div", { class: "confetti", "aria-hidden": "true" });
  for (let i = 0; i < 36; i++) {
    const c = el("i");
    c.style.left = Math.random() * 100 + "%";
    c.style.animationDelay = Math.random() * 0.5 + "s";
    c.style.animationDuration = 1.6 + Math.random() * 1.2 + "s";
    c.style.background = ["#FFD23F", "#FF5C8A", "#3DD68C", "#2F5BFF", "#B9A4FF", "#FF9E7A"][i % 6];
    bits.append(c);
  }
  box.replaceChildren(bits, el("div", { class: "card" },
    el("div", { class: "big", "aria-hidden": "true" }, "⭐"),
    el("div", { class: "praise" }, PRAISE[Math.floor(Math.random() * PRAISE.length)]),
    el("div", { class: "count" }, "Dziś zebrane gwiazdki: " + "⭐".repeat(Math.min(stars, 10)) + (stars > 10 ? " ×" + stars : ""))));
  box.hidden = false;
  clearTimeout(cheer.t);
  cheer.t = setTimeout(() => (box.hidden = true), 3200);
}
$("cheer").onclick = () => ($("cheer").hidden = true);

// On Friday to Sunday the child sees next week's school cards, one row per day
function renderWeek() {
  const dow = new Date().getDay();
  const panel = $("week-panel");
  panel.hidden = ![5, 6, 0].includes(dow);
  if (panel.hidden) return;
  const mon = addDays(today(), dow === 0 ? 1 : 8 - dow);
  const box = $("week"); box.replaceChildren();
  for (let i = 0; i < 5; i++) {
    const d = addDays(mon, i);
    const ts = S.tasks.filter((t) => t.member_id === S.me.id && t.due_date === d).sort((a, b) => a.title.localeCompare(b.title));
    const ul = el("ul", { class: "rows" });
    if (!ts.length) ul.append(el("li", { class: "empty" }, "Nic z dziennika."));
    for (const t of ts) ul.append(el("li", { class: "row" }, el("div", { class: "t" }, el("div", {}, t.title), t.details ? el("div", { class: "meta" }, t.details) : "")));
    box.append(el("div", { class: "day" }, el("h4", {}, dayLabel(d)), ul));
  }
}

// ---------- parent ----------
function renderRodzic() {
  const helps = S.tasks.filter((t) => st(t) === "help");
  $("help-panel").hidden = !helps.length;
  const hp = $("help"); hp.replaceChildren();
  for (const t of helps) hp.append(el("li", { class: "row" }, el("div", { class: "t" }, el("strong", {}, memberName(t.member_id)), " · " + t.title), el("div", { class: "acts" }, el("button", { class: "primary", onclick: () => act(t, "todo") }, "Pomogłem/am"))));

  $("t-num").textContent = S.tally[today()] || 0;
  const bars = $("t-bars"); bars.replaceChildren();
  const days = []; for (let i = 6; i >= 0; i--) { const x = new Date(); x.setDate(x.getDate() - i); days.push(x); }
  const vals = days.map((x) => S.tally[dayOf(x)] || 0);
  const max = Math.max(1, ...vals);
  days.forEach((x, i) => { const bar = el("i"); bar.style.height = Math.round((vals[i] / max) * 40) + "px"; bars.append(el("div", { title: vals[i] + " przypomnień" }, el("span", {}, String(vals[i])), bar, el("span", {}, ["nd", "pn", "wt", "śr", "cz", "pt", "sb"][x.getDay()]))); });

  const board = $("board"); board.replaceChildren();
  const doers = S.members;
  for (const m of doers) {
    const ts = S.tasks.filter((t) => t.member_id === m.id && visible(t)).sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
    if (!ts.length && m.role === "parent") continue;
    const row = (t) => {
      const s = st(t);
      return el("li", { class: "row" },
        el("div", { class: "t" }, el("div", {}, t.title), el("div", { class: "meta" }, [t.due_date ? dayLabel(t.due_date) : null, t.remind_at ? "przypomnienie " + t5(t.remind_at) : null, t.due ? "przed " + t5(t.due) : null, t.source === "eduvulcan" ? "z dziennika" : t.repeat === "daily" ? "codziennie" : "jednorazowo"].filter(Boolean).join(" · "))),
        el("span", { class: "pill " + CLS[s] }, LABEL[s]),
        el("div", { class: "acts" }, el("button", { class: "ghost", "aria-label": "Usuń zadanie " + t.title, onclick: () => removeTask(t) }, "Usuń")));
    };
    const school = ts.filter(isSchool), home = ts.filter((t) => !isSchool(t));
    const box = el("div", { class: "sects" });
    if (m.role === "child" || school.length) box.append(section("r-" + m.id + "-szkola", "📚 Zadania szkolne", school, row, "Brak zadań szkolnych."));
    if (m.role === "child" || home.length) box.append(section("r-" + m.id + "-dom", "🏠 Zadania domowe", home, row, "Brak obowiązków domowych."));
    board.append(el("h3", { style: "margin-top:8px" }, m.name), box);
  }
  if (!board.children.length) board.append(el("p", { class: "empty" }, "Dodaj domowników na dole strony, potem pierwsze zadanie."));

  const ml = $("members"); ml.replaceChildren();
  const sel = $("f-member"); const keep = sel.value; sel.replaceChildren();
  for (const m of S.members) {
    const acts = el("div", { class: "acts" });
    if (m.id !== S.me.id) acts.append(
      el("button", { class: "ghost", onclick: () => setRole(m, m.role === "parent" ? "child" : "parent") }, m.role === "parent" ? "Zrób wykonującym" : "Zrób rodzicem"),
      el("button", { class: "ghost", onclick: () => removeMember(m) }, "Usuń"));
    ml.append(el("li", { class: "row" }, el("div", { class: "t" }, m.name, el("span", { class: "meta" }, m.role === "parent" ? "  · rodzic" : "  · wykonuje zadania")), acts));
    sel.append(el("option", { value: m.id }, m.name));
  }
  const kids = childIds();
  if (kids.length >= 2) sel.append(el("option", { value: ALL_KIDS }, kidsLabel(kids)));
  if (keep) sel.value = keep; else { const firstChild = S.members.find((m) => m.role === "child"); if (firstChild) sel.value = firstChild.id; }
  renderAlarms();
  renderSchool();
}

// ---------- alarms (budzik): the parent sets them, the child only turns one off by solving a challenge ----------
const DAYS = [[1, "pn"], [2, "wt"], [3, "śr"], [4, "cz"], [5, "pt"], [6, "sb"], [0, "nd"]];
function daysLabel(days) {
  const set = new Set(days), on = DAYS.filter(([d]) => set.has(d)).map(([, n]) => n);
  if (on.length === 7) return "codziennie";
  if (on.join() === "pn,wt,śr,cz,pt") return "pn–pt";
  if (on.join() === "sb,nd") return "weekend";
  return on.join(", ");
}
const KIND = { "math:1": "matematyka łatwa", "math:2": "matematyka do 100", "math:3": "tabliczka mnożenia", "code:1": "przepisz kod", "code:2": "przepisz kod", "code:3": "przepisz kod" };
const kindLabel = (a) => a.problems + " × " + (KIND[a.challenge + ":" + a.level] || a.challenge);
const toMin = (t) => { const [h, m] = t5(t).split(":").map(Number); return h * 60 + m; };
// Same rule as the server: from the set time for 60 minutes on a chosen weekday, until solved today
function ringingNow(a) {
  if (!a.enabled || a.dismissed_day === today()) return false;
  const d = new Date();
  if (!a.days.includes(d.getDay())) return false;
  const since = d.getHours() * 60 + d.getMinutes() - toMin(a.at);
  return since >= 0 && since < 60;
}

let editingAlarm = null;
function renderAlarms() {
  const kids = S.members.filter((m) => m.role === "child");
  const list = $("alarms"); list.replaceChildren();
  for (const a of S.alarms) {
    const ring = ringingNow(a), off = a.dismissed_day === today();
    const state = !a.enabled ? "wyłączony przez rodzica" : ring ? "dzwoni teraz" : off ? "dziś wyłączony o " + hm(Date.parse(a.dismissed_at)) : "";
    list.append(el("li", { class: "row" + (a.enabled ? "" : " off") },
      el("div", { class: "t" }, el("div", {}, el("strong", {}, memberName(a.member_id)), " · " + t5(a.at)),
        el("div", { class: "meta" }, daysLabel(a.days) + " · " + kindLabel(a))),
      state ? el("span", { class: "pill " + (ring ? "s-help" : off ? "s-done" : "s-todo") }, state) : "",
      el("div", { class: "acts" },
        el("button", { class: "ghost", type: "button", "aria-pressed": String(a.enabled), onclick: () => saveAlarm(a, { enabled: !a.enabled }, a.enabled ? "Budzik wyłączony." : "Budzik włączony.") }, a.enabled ? "Wyłącz" : "Włącz"),
        el("button", { class: "ghost", type: "button", onclick: () => editAlarm(a) }, "Zmień"),
        el("button", { class: "ghost", type: "button", "aria-label": "Usuń budzik " + memberName(a.member_id) + " " + t5(a.at), onclick: () => removeAlarm(a) }, "Usuń"))));
  }
  if (!S.alarms.length) list.append(el("li", { class: "empty" }, kids.length ? "Nie ma jeszcze budzików." : "Najpierw dodaj dziecko w „Domownicy”."));

  const sel = $("a-member"), keep = sel.value; sel.replaceChildren();
  for (const m of kids) sel.append(el("option", { value: m.id }, m.name));
  if (kids.length >= 2 && !editingAlarm) sel.append(el("option", { value: ALL_KIDS }, kidsLabel(kids.map((m) => m.id))));
  if (keep && [...sel.options].some((o) => o.value === keep)) sel.value = keep;
  const box = $("a-days");
  if (!box.querySelector("input")) for (const [d, n] of DAYS) box.append(el("label", { class: "day-chip" }, el("input", { type: "checkbox", value: d, checked: d >= 1 && d <= 5 }), el("span", {}, n)));
  $("a-head").textContent = editingAlarm ? "Zmień budzik: " + memberName(editingAlarm.member_id) : "Nowy budzik";
  $("a-save").textContent = editingAlarm ? "Zapisz zmiany" : "Ustaw budzik";
  $("a-cancel").hidden = !editingAlarm;
}
function formAlarm() {
  const days = [...$("a-days").querySelectorAll("input:checked")].map((i) => Number(i.value));
  const [challenge, level] = $("a-kind").value.split(":");
  return { at: $("a-at").value, days, challenge, level: Number(level), problems: Number($("a-problems").value), enabled: true };
}
function editAlarm(a) {
  editingAlarm = a;
  $("a-member").value = a.member_id;
  $("a-at").value = t5(a.at);
  $("a-problems").value = String(a.problems);
  $("a-kind").value = a.challenge === "code" ? "code:1" : a.challenge + ":" + a.level;
  for (const i of $("a-days").querySelectorAll("input")) i.checked = a.days.includes(Number(i.value));
  renderAlarms();
  $("a-member").disabled = true;
  $("a-head").scrollIntoView({ behavior: "smooth", block: "center" });
}
function stopEditing() { editingAlarm = null; $("a-member").disabled = false; renderAlarms(); }
$("a-cancel").onclick = stopEditing;
async function saveAlarm(a, patch, msg) {
  // A changed alarm may ring again today, even if it was already turned off this morning
  const { error } = await sb.from("dz_alarms").update({ ...patch, dismissed_day: null, updated_at: new Date().toISOString() }).eq("id", a.id);
  if (error) { toast(errMsg(error)); return false; }
  await loadAll(); render(); toast(msg);
  return true;
}
$("alarm-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = formAlarm();
  if (!f.at) { $("a-at").focus(); return; }
  if (!f.days.length) { toast("Zaznacz co najmniej jeden dzień."); return; }
  if (editingAlarm) { if (await saveAlarm(editingAlarm, f, "Zapisano budzik.")) stopEditing(); return; }
  const who = $("a-member").value;
  const ids = who === ALL_KIDS ? childIds() : who ? [who] : [];
  if (!ids.length) { toast("Wybierz dziecko."); return; }
  const { error } = await sb.from("dz_alarms").insert(ids.map((member_id) => ({ ...f, family_id: S.family.id, member_id })));
  if (error) { toast(errMsg(error)); return; }
  await loadAll(); render(); toast("Budzik " + f.at + " (" + daysLabel(f.days) + ") dla: " + namesList(ids) + ".");
});
async function removeAlarm(a) {
  if (!confirmTwice("alarm-" + a.id, "Stuknij „Usuń” jeszcze raz, aby usunąć budzik " + t5(a.at) + ".")) return;
  const { error } = await sb.from("dz_alarms").delete().eq("id", a.id);
  if (error) toast(errMsg(error)); else { if (editingAlarm?.id === a.id) stopEditing(); await loadAll(); render(); toast("Usunięto budzik."); }
}

// Loud ring while the app is open. It plays through an <audio> element (media), not Web Audio, because on an iPhone
// media plays even with the silent switch on, while Web Audio stays muted. Its loudness is the phone's media volume:
// the browser cannot raise it above that. Browsers start sound only after a tap, so any tap on the ring screen retries.
function wavBlob(seconds, sample) {
  const rate = 22050, n = Math.round(rate * seconds), buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const str = (o, x) => { for (let i = 0; i < x.length; i++) v.setUint8(o + i, x.charCodeAt(i)); };
  str(0, "RIFF"); v.setUint32(4, 36 + n * 2, true); str(8, "WAVEfmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); str(36, "data"); v.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, sample(i / rate))) * 32767, true);
  return URL.createObjectURL(new Blob([buf], { type: "audio/wav" }));
}
// 4 piercing beeps (2.6 / 3.2 kHz, where phone speakers and ears are loudest), short pause, full scale square wave
function alarmSample(t) {
  const k = Math.floor(t / 0.2), inBeep = t - k * 0.2;
  if (k >= 4 || inBeep > 0.16) return 0;
  const f = k % 2 ? 3200 : 2600, edge = Math.min(1, inBeep / 0.004, (0.16 - inBeep) / 0.004);
  return (Math.sin(2 * Math.PI * f * t) >= 0 ? 0.98 : -0.98) * edge;
}
const Ring = {
  audio: null, urls: null, timer: null, armed: false,
  el() {
    if (!this.audio) {
      this.urls = { alarm: wavBlob(1.15, alarmSample), silent: wavBlob(1, () => 0) };
      this.audio = new Audio(); this.audio.loop = true; this.audio.preload = "auto"; this.audio.setAttribute("playsinline", "");
    }
    return this.audio;
  },
  play(url) {
    try { if (navigator.audioSession) navigator.audioSession.type = "playback"; } catch {}
    const a = this.el();
    if (a.src !== this.urls[url]) a.src = this.urls[url];
    a.volume = 1;
    return a.play().catch(() => {});
  },
  start() {
    this.play("alarm");
    if (!this.timer) this.timer = setInterval(() => { try { navigator.vibrate?.([700, 300, 700]); } catch {} }, 1700);
  },
  // Night mode: started by a tap, keeps the audio element playing silence so it may ring later without another tap
  arm() { this.armed = true; this.play("silent"); },
  disarm() { this.armed = false; if (!this.timer) this.audio?.pause(); },
  stop() {
    clearInterval(this.timer); this.timer = null;
    try { navigator.vibrate?.(0); } catch {}
    if (this.armed) this.play("silent"); else this.audio?.pause();
  },
};
let testT = null;
function testSound() {
  if (AL.alarm) return;
  Ring.play("alarm"); clearTimeout(testT);
  testT = setTimeout(() => { if (!AL.alarm) Ring.stop(); }, 3000);
  toast("Tak głośno zadzwoni budzik. Za cicho? Podgłoś telefon przyciskami głośności.");
}

// Night mode: the app stays open on the charger with a dark clock and rings at the exact time, even on silent
const nextAlarm = () => {
  if (!S.me) return null;
  const now = new Date(), cur = now.getHours() * 60 + now.getMinutes();
  let best = null;
  for (const a of S.alarms.filter((x) => x.member_id === S.me.id && x.enabled)) for (let i = 0; i < 8; i++) {
    const dow = (now.getDay() + i) % 7;
    if (!a.days.includes(dow) || (i === 0 && (toMin(a.at) <= cur || a.dismissed_day === today()))) continue;
    const mins = i * 1440 + toMin(a.at) - cur;
    if (!best || mins < best.mins) best = { a, mins, i };
    break;
  }
  return best;
};
const NIGHT = { on: false, lock: null, timer: null };
async function nightOn() {
  NIGHT.on = true;
  Ring.arm();
  $("night").hidden = false;
  document.documentElement.classList.add("ringing");
  renderNight();
  clearInterval(NIGHT.timer); NIGHT.timer = setInterval(renderNight, 15000);
  document.documentElement.requestFullscreen?.().catch(() => {});
  try { NIGHT.lock = await navigator.wakeLock?.request("screen"); } catch {}
}
function nightOff() {
  NIGHT.on = false; clearInterval(NIGHT.timer);
  Ring.disarm();
  try { NIGHT.lock?.release(); } catch {} NIGHT.lock = null;
  $("night").hidden = true;
  if (!AL.alarm) document.documentElement.classList.remove("ringing");
  try { if (document.fullscreenElement) document.exitFullscreen(); } catch {}
}
function renderNight() {
  if (!NIGHT.on) return;
  $("nt-clock").textContent = hm(Date.now());
  const n = nextAlarm();
  $("nt-next").textContent = n ? "Budzik: " + (n.i === 0 ? "dziś" : n.i === 1 ? "jutro" : WD[(new Date().getDay() + n.i) % 7]) + " o " + t5(n.a.at) : "Brak włączonego budzika.";
}
$("nt-off").onclick = nightOff;
// The wake lock drops when the page is hidden; take it again when the child comes back
document.addEventListener("visibilitychange", async () => { if (NIGHT.on && !document.hidden) { try { NIGHT.lock = await navigator.wakeLock?.request("screen"); } catch {} } });

const AL = { alarm: null, busy: false, lock: null };
async function checkAlarm() {
  const a = S.me && S.alarms.find((x) => x.member_id === S.me.id && ringingNow(x));
  if (!a) { if (AL.alarm && !AL.busy) closeAlarm(); return; }
  if (AL.alarm?.id === a.id) return;
  AL.alarm = a;
  $("al-time").textContent = t5(a.at);
  $("al-head").textContent = "Pobudka, " + S.me.name + "!";
  $("alarm").hidden = false;
  document.documentElement.classList.add("ringing");
  Ring.start();
  try { AL.lock = await navigator.wakeLock?.request("screen"); } catch {}
  showChallenge(await alarmRpc("dz_alarm_challenge", { p_alarm: a.id }));
}
async function alarmRpc(fn, args) {
  const { data, error } = await sb.rpc(fn, args);
  if (error) { $("al-msg").textContent = "Brak połączenia. Sprawdź internet i spróbuj jeszcze raz."; return null; }
  return data;
}
function showChallenge(c, msg = "") {
  if (!c) return;
  if (!c.ringing) { finishAlarm(c.done); return; }
  const code = c.kind === "code";
  $("al-sub").textContent = code ? "Przepisz kod, żeby wyłączyć budzik." : "Rozwiąż zadania, żeby wyłączyć budzik.";
  $("al-dots").replaceChildren(...Array.from({ length: c.total }, (_, i) => el("i", { class: i < c.solved ? "on" : "" })));
  $("al-progress").textContent = (code ? "Kod " : "Zadanie ") + (c.solved + 1) + " z " + c.total;
  $("al-q").replaceChildren(code ? el("span", { class: "al-code" }, c.q.replace(/(.{3})(?=.)/g, "$1 ")) : el("span", {}, c.q + " = ?"));
  const inp = $("al-input");
  inp.value = "";
  inp.setAttribute("inputmode", code ? "text" : "numeric");
  inp.setAttribute("autocapitalize", code ? "characters" : "off");
  inp.placeholder = code ? "Wpisz kod" : "Wynik";
  $("al-msg").textContent = msg;
  inp.focus();
}
$("al-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  Ring.start();
  const v = $("al-input").value.trim();
  if (!v || !AL.alarm || AL.busy) { $("al-input").focus(); return; }
  AL.busy = true; $("al-ok").disabled = true;
  const r = await alarmRpc("dz_alarm_answer", { p_alarm: AL.alarm.id, p_answer: v });
  AL.busy = false; $("al-ok").disabled = false;
  if (!r) return;
  const card = $("alarm").querySelector(".alarm-card");
  if (r.correct === false) { card.classList.remove("shake"); void card.offsetWidth; card.classList.add("shake"); }
  showChallenge(r, r.correct ? "Dobrze! Następne." : "Nie tym razem. Masz nowe zadanie.");
});
// Any tap on the ringing screen turns the sound on (browsers block sound until the first tap)
$("alarm").addEventListener("pointerdown", () => Ring.start());
function closeAlarm() {
  Ring.stop();
  try { AL.lock?.release(); } catch {}
  AL.alarm = null; AL.lock = null;
  $("alarm").hidden = true;
  document.documentElement.classList.remove("ringing");
}
async function finishAlarm(solved) {
  const a = AL.alarm;
  closeAlarm();
  if (!a) return;
  const mine = S.alarms.find((x) => x.id === a.id);
  if (mine) { mine.dismissed_day = today(); mine.dismissed_at = new Date().toISOString(); }
  if (NIGHT.on) nightOff();
  if (solved) morning();
  render();
}
function morning() {
  const box = $("cheer");
  box.replaceChildren(el("div", { class: "card" },
    el("div", { class: "big", "aria-hidden": "true" }, "☀️"),
    el("div", { class: "praise" }, "Dzień dobry, " + (S.me?.name || "") + "!"),
    el("div", { class: "count" }, "Budzik wyłączony. Wstajemy!")));
  box.hidden = false;
  clearTimeout(cheer.t);
  cheer.t = setTimeout(() => (box.hidden = true), 4000);
}
setInterval(() => { if (!document.hidden) checkAlarm(); }, 10000);

// ---------- school dziennik (eduVULCAN) ----------
async function dziennik(action, extra = {}) {
  const { data, error } = await sb.functions.invoke("dz-dziennik", { body: { action, ...extra } });
  if (error) {
    let msg = errMsg(error);
    try { const j = await error.context.json(); if (j?.error) msg = j.error; } catch {}
    throw new Error(msg);
  }
  return data;
}
let schoolBusy = false;
async function schoolDo(action, extra, okMsg) {
  if (schoolBusy) return;
  schoolBusy = true; renderSchool();
  try {
    S.school = await dziennik(action, extra);
    await loadAll();
    const r = S.school.result;
    if (S.school.last_error) toast(S.school.last_error);
    else if (okMsg) toast(okMsg + (r ? " Nowe karty: " + r.added + "." : ""));
  } catch (e) { toast(errMsg(e)); }
  schoolBusy = false; render();
}
let schoolAsked = false;
function renderSchool() {
  const box = $("school");
  if (!S.school) {
    if (!schoolAsked) { schoolAsked = true; dziennik("status").then((d) => { S.school = d; render(); }).catch((e) => { box.replaceChildren(el("p", { class: "err" }, "Nie mogę sprawdzić połączenia z dziennikiem: " + errMsg(e))); }); }
    return;
  }
  box.replaceChildren();
  if (schoolBusy) { box.append(el("p", { class: "muted small" }, "Łączę się z eduVULCAN…")); return; }
  if (!S.school.linked) {
    box.append(
      el("p", { class: "muted small" }, "Zadania domowe i sprawdziany z najbliższych 2 tygodni same pojawią się jako karty dziecka. Dziecko widzi kartę 3 dni wcześniej, przypomnienie o 16:00 przychodzi przez 2 dni przed terminem. Nie podajesz hasła: aplikacja dopisuje się w eduVULCAN jako kolejny telefon."),
      el("ol", {},
        el("li", {}, "Najprościej na komputerze: otwórz tę aplikację w przeglądarce, dołącz kodem rodziny i wybierz siebie."),
        el("li", {}, "W drugiej karcie zaloguj się na eduvulcan.pl kontem rodzica."),
        el("li", {}, "W tej samej karcie wejdź na ", el("code", {}, "eduvulcan.pl/api/ap"), "."),
        el("li", {}, "Naciśnij Ctrl+U (źródło strony), potem Ctrl+A i Ctrl+C."),
        el("li", {}, "Wróć tutaj, wklej poniżej (Ctrl+V) i stuknij „Połącz”. Zrób to od razu, te dane ważne są krótko.")),
      el("p", { class: "muted small" }, "Na Androidzie w Chrome możesz też wpisać w pasek adresu ", el("code", {}, "view-source:https://eduvulcan.pl/api/ap"), ", zaznaczyć wszystko i skopiować."));
    // Kept across re-renders so a live refresh does not wipe the pasted text
    const ta = (renderSchool.ta ??= el("textarea", { rows: "3", placeholder: "Wklej tu skopiowaną stronę", "aria-label": "Dane z eduvulcan.pl/api/ap", spellcheck: "false" }));
    box.append(ta, el("div", { class: "acts" }, el("button", { class: "primary", onclick: () => {
      if (!ta.value.trim()) { ta.focus(); return; }
      const ap = ta.value; ta.value = "";
      schoolDo("link", { ap }, "Dziennik połączony.");
    } }, "Połącz")));
    return;
  }
  const accs = S.school.accounts || [];
  for (const a of accs) {
    const sel = el("select", { "aria-label": "Czyje karty dla: " + a.name });
    sel.append(el("option", { value: "" }, "— nie pobieraj —"));
    for (const m of S.members) sel.append(el("option", { value: m.id }, m.name));
    sel.value = a.member_id || "";
    sel.onchange = () => schoolDo("map", { pupil_id: a.pupil_id, member_id: sel.value || null }, sel.value ? "Karty trafią do: " + memberName(sel.value) + "." : "Nie pobieram już dla: " + a.name + ".");
    box.append(el("label", {}, a.name + (a.class ? " · klasa " + a.class : "") + " → karty dla", sel));
  }
  if (accs.length && !accs.some((a) => a.member_id)) box.append(el("p", { class: "note" }, "Wybierz, komu dawać karty, wtedy zacznę pobierać."));
  if (S.school.last_error) box.append(el("p", { class: "err" }, S.school.last_error));
  box.append(el("p", { class: "muted small" }, S.school.last_sync_at ? "Ostatnio pobrane: " + dayLabel(dayOf(new Date(S.school.last_sync_at))) + " " + hm(Date.parse(S.school.last_sync_at)) + ". Pobieram sam 3 razy dziennie." : "Jeszcze nic nie pobrano."));
  box.append(el("div", { class: "acts" },
    el("button", { class: "primary", onclick: () => schoolDo("sync", {}, "Pobrane.") }, "Pobierz teraz"),
    el("button", { class: "ghost", onclick: () => { if (confirmTwice("school-unlink", "Stuknij „Odłącz” jeszcze raz. Usunę też niezrobione karty z dziennika.")) schoolDo("unlink", {}, "Dziennik odłączony."); } }, "Odłącz dziennik")));
}

const pending = new Set();
function confirmTwice(key, msg) { if (pending.has(key)) { pending.delete(key); return true; } pending.add(key); toast(msg); setTimeout(() => pending.delete(key), 4000); return false; }
async function removeTask(t) {
  if (!confirmTwice(t.id, "Stuknij „Usuń” jeszcze raz, aby usunąć „" + t.title + "”.")) return;
  const { error } = await sb.from("dz_tasks").delete().eq("id", t.id);
  if (error) toast(errMsg(error)); else { S.tasks = S.tasks.filter((x) => x.id !== t.id); render(); toast("Usunięto."); }
}
async function removeMember(m) {
  if (!confirmTwice(m.id, "Stuknij „Usuń” jeszcze raz. Usunę też zadania osoby " + m.name + ".")) return;
  const { error } = await sb.from("dz_members").delete().eq("id", m.id);
  if (error) toast(errMsg(error)); else { await loadAll(); render(); }
}

async function setRole(m, role) {
  const { error } = await sb.from("dz_members").update({ role }).eq("id", m.id);
  if (error) { toast(errMsg(error)); return; }
  await loadAll(); render();
  toast(role === "parent" ? m.name + " jest teraz rodzicem." : m.name + " wykonuje teraz zadania.");
}

async function bumpTally(delta) {
  const d = today(); const n = Math.max(0, (S.tally[d] || 0) + delta);
  S.tally[d] = n; render();
  const { error } = await sb.from("dz_tally").upsert({ family_id: S.family.id, day: d, count: n });
  if (error) toast("Nie udało się zapisać licznika.");
}
$("t-plus").onclick = () => bumpTally(1);
$("t-minus").onclick = () => bumpTally(-1);

$("m-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const name = $("m-name").value.trim(); if (!name) return;
  const { error } = await sb.from("dz_members").insert({ family_id: S.family.id, name, role: $("m-role").value });
  if (error) { toast(errMsg(error)); return; }
  $("m-name").value = ""; await loadAll(); render();
});

$("add-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const title = $("f-title").value.trim(), who = $("f-member").value;
  if (!title || !who) { toast("Wpisz polecenie i wybierz osobę."); return; }
  const ids = who === ALL_KIDS ? childIds() : [who];
  if (!ids.length) { toast("Wybierz osobę."); return; }
  const steps = $("f-steps").value.split("\n").map((s) => s.trim()).filter(Boolean).slice(0, 8);
  const base = { family_id: S.family.id, title, steps, due: $("f-due").value || null, remind_at: $("f-remind").value || null, repeat: $("f-repeat").checked ? "daily" : "once", status: "todo", status_day: today() };
  const { error } = await sb.from("dz_tasks").insert(ids.map((member_id) => ({ ...base, member_id })));
  if (error) { toast(errMsg(error)); return; }
  $("f-title").value = ""; $("f-steps").value = "";
  await loadAll(); render(); toast("Dodano zadanie dla: " + namesList(ids) + ".");
});

// ---------- settings & push ----------
const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
function renderSettings() {
  const supported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  const perm = supported ? Notification.permission : "unsupported";
  $("notif-state").textContent = !supported ? (isIOS && !standalone ? "Najpierw dodaj aplikację do ekranu początkowego." : "Ta przeglądarka nie obsługuje powiadomień push.")
    : perm === "granted" ? "Powiadomienia są włączone na tym telefonie." : perm === "denied" ? "Powiadomienia są zablokowane w ustawieniach przeglądarki lub telefonu." : "Powiadomienia są wyłączone.";
  $("notif-on").hidden = !supported || perm === "denied";
  $("notif-on").textContent = perm === "granted" ? "Odśwież rejestrację powiadomień" : "Włącz powiadomienia";
  $("ios-hint").hidden = !(isIOS && !standalone);
  $("invite-panel").hidden = !S.isParent;
  if (S.isParent && S.family) {
    $("invite-code").textContent = S.family.invite_code;
    $("invite-link").textContent = inviteLink();
  }
  $("me-line").textContent = "Ten telefon należy do: " + (S.me ? S.me.name : "—");
  $("switch-member").textContent = S.isParent ? "Zmień osobę na tym telefonie" : "Zmień osobę (rodzic)";
  renderSkins();
}
// ---------- skins: each member picks a look, remembered per person on this device ----------
const SKINS = [
  { id: "morski", name: "Morski", sw: ["#0E6B66", "#EEF3F4"] },
  { id: "cieply", name: "Ciepły poranek", sw: ["#C2532E", "#F8F1E7"] },
  { id: "nocny", name: "Nocny granat", sw: ["#C8F25A", "#0C0F22"] },
  { id: "klocki", name: "Kolorowe klocki", sw: ["#FF5C8A", "#FFD23F"] },
  { id: "piksele", name: "Pikselowy świat", sw: ["#5DB548", "#8B5A2B"] },
  { id: "plac", name: "Klockowy plac", sw: ["#00A2FF", "#FF4D4D"] },
  { id: "kotek", name: "Pastelowy kotek", sw: ["#FFB7CC", "#B79CE0"] },
  { id: "ninja", name: "Wojownik ninja", sw: ["#E1261C", "#141414"] },
];
const DARK = ["nocny", "ninja"];
const skinKey = () => "dz-skin-" + (S.me ? S.me.id : "none");
function skinOf() {
  let k = null;
  try { k = localStorage.getItem(skinKey()); } catch {}
  return SKINS.some((x) => x.id === k) ? k : S.me && !S.isParent ? "klocki" : "morski";
}
function applySkin() {
  const k = skinOf(), link = $("skin-css"), root = document.documentElement;
  const href = k === "morski" ? "" : "themes/" + k + ".css";
  if (link.getAttribute("href") !== href) { if (href) link.setAttribute("href", href); else link.removeAttribute("href"); }
  if (k === "morski") delete root.dataset.theme; else root.dataset.theme = DARK.includes(k) ? "dark" : "light";
  try { localStorage.setItem("dz-skin", k); } catch {}
}
function renderSkins() {
  const box = $("skins"); box.replaceChildren();
  const cur = skinOf();
  for (const x of SKINS) box.append(el("button", { class: "chip skin", "aria-pressed": String(x.id === cur), onclick: () => {
    try { localStorage.setItem(skinKey(), x.id); } catch {}
    applySkin(); renderSkins();
  } }, el("i", { style: "background:linear-gradient(135deg," + x.sw[0] + " 50%," + x.sw[1] + " 50%)" }), x.name));
}
const inviteLink = () => location.origin + location.pathname + "?kod=" + S.family.invite_code;
$("invite-copy").onclick = async () => { try { await navigator.clipboard.writeText(inviteLink()); toast("Skopiowano link."); } catch { toast("Skopiuj link ręcznie."); } };
// Only a parent may change who uses this phone: a child's phone asks for the family code, which only parents can see
const switchMember = () => { S.me = null; S.isParent = false; render(); };
$("switch-member").onclick = () => {
  if (S.isParent) return switchMember();
  $("switch-lock").hidden = false; $("switch-hint").hidden = false; $("switch-code").focus();
};
$("switch-lock").addEventListener("submit", (e) => {
  e.preventDefault();
  const ok = S.family && $("switch-code").value.trim().toUpperCase() === String(S.family.invite_code).toUpperCase();
  $("switch-code").value = "";
  if (!ok) { toast("To nie jest kod rodziny. Poproś rodzica."); return; }
  $("switch-lock").hidden = true; $("switch-hint").hidden = true;
  switchMember();
});

function b64urlToUint8(s) { const p = "=".repeat((4 - (s.length % 4)) % 4); const b = atob((s + p).replace(/-/g, "+").replace(/_/g, "/")); return Uint8Array.from(b, (c) => c.charCodeAt(0)); }
async function enablePush() {
  try {
    const perm = await Notification.requestPermission();
    if (perm !== "granted") { toast("Bez zgody na powiadomienia przypomnienia przyjdą tylko przy otwartej aplikacji."); render(); return; }
    const reg = await navigator.serviceWorker.ready;
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      const { data: key, error: keyErr } = await sb.rpc("dz_vapid_public");
      if (keyErr || !key) throw new Error("serwer powiadomień nie jest jeszcze gotowy");
      sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64urlToUint8(key) });
    }
    const j = sub.toJSON();
    const { error } = await sb.from("dz_push_subs").upsert({ endpoint: j.endpoint, user_id: S.uid, family_id: S.family.id, p256dh: j.keys.p256dh, auth: j.keys.auth });
    if (error) throw error;
    toast("Powiadomienia włączone.");
  } catch (e) { toast("Nie udało się włączyć powiadomień: " + errMsg(e)); }
  render();
}
$("notif-on").onclick = enablePush;

// Actions from a tapped notification: ?task=<id>&a=done|snooze15 (snooze20 kept for notifications sent before the change)
async function handleUrlAction() {
  const p = new URLSearchParams(location.search);
  const code = p.get("kod"); if (code && !S.device) $("join-code").value = code;
  const id = p.get("task"), a = p.get("a");
  if (id && a && S.device) {
    const t = S.tasks.find((x) => x.id === id);
    if (t) {
      if (a === "done" && (await act(t, "done"))) cheer();
      const snoozeMin = { snooze15: 15, snooze20: 20 }[a];
      if (snoozeMin) { const ms = Date.now() + snoozeMin * 60000; if (await act(t, "snoozed", ms)) toast("OK, wrócę do Ciebie o " + hm(ms) + "."); }
    }
  }
  if (id || code) history.replaceState(null, "", location.pathname + location.hash);
}
navigator.serviceWorker?.addEventListener("message", async (e) => { if (e.data?.type === "open") { history.replaceState(null, "", e.data.url); await loadAll(); await handleUrlAction(); render(); } });

// ---------- boot ----------
(async () => {
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  let { data: { session } } = await sb.auth.getSession();
  if (!session) {
    const { data, error } = await sb.auth.signInAnonymously();
    if (error) { status("Nie mogę się połączyć: " + errMsg(error)); return; }
    session = data.session;
  }
  S.uid = session.user.id;
  await loadAll();
  status(null);
  subscribe();
  await handleUrlAction();
  render();
})();
