// Service worker: app-shell cache + push notifications
const CACHE = "dz-v9";
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

self.addEventListener("push", (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch { d = { title: "Domowe Zadania", body: e.data && e.data.text() }; }
  const actions = d.kind === "help" ? [] : [{ action: "done", title: "Zrobione" }, { action: "snooze15", title: "Za 15 min" }];
  e.waitUntil(self.registration.showNotification(d.title || "Domowe Zadania", {
    body: d.body || "", icon: "icons/icon-192.png", badge: "icons/badge-96.png", tag: d.task || undefined, renotify: true,
    data: { task: d.task, kind: d.kind }, actions,
  }));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const { task, kind } = e.notification.data || {};
  const params = new URLSearchParams();
  if (task && e.action) { params.set("task", task); params.set("a", e.action); }
  const url = new URL("./" + (params.toString() ? "?" + params : "") + (kind === "help" ? "#rodzic" : "#moje"), self.registration.scope).href;
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const w = wins.find((c) => c.url.startsWith(self.registration.scope));
    if (w) { await w.focus(); w.postMessage({ type: "open", url }); }
    else await self.clients.openWindow(url);
  })());
});
