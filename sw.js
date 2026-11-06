// Service worker: app-shell cache + push notifications
const CACHE = "dz-v16";
const SHELL = ["./", "index.html", "styles.css", "app.js", "config.js", "manifest.webmanifest", "icons/icon-192.png",
  "themes/cieply.css", "themes/nocny.css", "themes/klocki.css", "themes/piksele.css", "themes/plac.css", "themes/kotek.css", "themes/ninja.css"];

self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())); });

// Network first for the shell so updates arrive; cache as offline fallback. API calls are never cached.
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r; }).catch(() => caches.match(e.request)));
});

// Alarm notification. Android plays a sound only for a NEW notification: an update of one with the same tag
// is often quiet (and is grouped), so every ring gets its own tag and the previous ring is closed afterwards.
async function showAlarm(d) {
  const tag = (d.task || "alarm") + "-" + Date.now();
  await self.registration.showNotification(d.title || "⏰ Budzik", {
    body: d.body || "", icon: "icons/icon-192.png", badge: "icons/badge-96.png", tag, renotify: true,
    data: { task: d.task, kind: "alarm" }, actions: [], requireInteraction: true, silent: false,
    vibrate: [900, 300, 900, 300, 900, 300, 900],
  });
  for (const n of await self.registration.getNotifications()) if (n.data?.kind === "alarm" && n.data?.task === d.task && n.tag !== tag) n.close();
}

self.addEventListener("push", (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch { d = { title: "Domowe Zadania", body: e.data && e.data.text() }; }
  // An alarm has no buttons on purpose: it can only be turned off in the app, by solving the challenge
  if (d.kind === "alarm") { e.waitUntil(showAlarm(d)); return; }
  const actions = d.kind === "help" || d.kind === "nudge" ? [] : [{ action: "done", title: "Zrobione" }, { action: "snooze15", title: "Za 15 min" }];
  if (d.kind === "nudge" && typeof d.count === "number") self.navigator.setAppBadge?.(d.count).catch(() => {});
  e.waitUntil(self.registration.showNotification(d.title || "Domowe Zadania", {
    body: d.body || "", icon: "icons/icon-192.png", badge: "icons/badge-96.png", tag: d.task || undefined, renotify: true,
    data: { task: d.task, kind: d.kind }, actions,
    // Task reminders stay on screen until tapped, with sound and a vibration
    ...(d.kind === "nudge" || d.kind === "remind" || d.kind === "snooze" ? { requireInteraction: true, silent: false, vibrate: [400, 200, 400] } : {}),
  }));
});

// "Test na zablokowanym ekranie": the page asks, the worker waits so the child can lock the phone first.
// Same notification as a real alarm, so it shows whether the phone's settings let it make a sound.
self.addEventListener("message", (e) => {
  if (e.data?.type !== "alarm-test") return;
  e.waitUntil(new Promise((r) => setTimeout(r, (e.data.delay || 15) * 1000)).then(() => showAlarm({
    title: "⏰ Próbny budzik", body: "Tak przyjdzie budzik na zablokowanym ekranie. Słychać dźwięk? Jeśli nie, sprawdź ustawienia powiadomień Chrome.", task: "alarm-test",
  })));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const { task, kind } = e.notification.data || {};
  const params = new URLSearchParams();
  if (task && e.action && kind !== "alarm") { params.set("task", task); params.set("a", e.action); }
  const url = new URL("./" + (params.toString() ? "?" + params : "") + (kind === "help" ? "#rodzic" : "#moje"), self.registration.scope).href;
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const w = wins.find((c) => c.url.startsWith(self.registration.scope));
    if (w) { await w.focus(); w.postMessage({ type: "open", url }); }
    else await self.clients.openWindow(url);
  })());
});
