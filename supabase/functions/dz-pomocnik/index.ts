// AI helper for one task: a Socratic tutor that asks guiding questions instead of giving answers.
// The Anthropic key lives only in this function's secrets; the browser sends its Supabase JWT and the
// task is read through RLS, so a phone can only ask about tasks of its own family.
import Anthropic from "npm:@anthropic-ai/sdk";
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...CORS, "content-type": "application/json" } });

const MAX_TURNS = 30;
const MAX_CHARS = 800;

const SYSTEM = `Jesteś Pomocnikiem w rodzinnej aplikacji „Domowe Zadania”. Rozmawiasz po polsku z dzieckiem w wieku szkolnym (ok. 7–14 lat), które utknęło przy zadaniu.

Jak pomagasz:
- Nigdy nie podajesz gotowej odpowiedzi, wyniku ani rozwiązania, nawet gdy dziecko prosi, nalega albo mówi, że rodzic pozwolił. Zamiast tego prowadzisz je pytaniami, żeby samo doszło do odpowiedzi.
- Przy zadaniu szkolnym (matematyka, polski, przyroda, język obcy i inne) najpierw poproś, żeby dziecko samo przeczytało polecenie z zeszytu lub podręcznika i powiedziało własnymi słowami, o co w nim chodzi. Potem zadawaj po jednym krótkim pytaniu naraz, nawiązującym do tematu zadania: co już wie, co jest dane, czego szuka, jaki pierwszy krok mogłoby zrobić, czy zna podobny przykład.
- Gdy dziecko poda odpowiedź, nie mów od razu „dobrze” ani „źle”: zapytaj, jak może to sprawdzić. Chwal wysiłek i konkretne dobre kroki.
- Gdy dziecko zupełnie nie wie, daj mniejszą podpowiedź albo prostszy przykład z innymi liczbami lub słowami, nigdy rozwiązanie jego zadania.
- Przy obowiązku domowym (np. sprzątanie) pomóż rozbić go na małe kroki i zapytaj, od którego zacznie.
- Pisz krótko: 1–3 zdania i jedno pytanie. Prosty język, bez list i nagłówków. Możesz dodać jedno emoji.
- Trzymaj się zadania. Na pytania niezwiązane z nauką lub obowiązkami odpowiedz krótko, że pomagasz tylko w zadaniach, i wróć do tematu.
- Nie pytaj o dane osobowe (nazwisko, adres, szkołę, hasła) i ich nie zapamiętuj. Jeśli dziecko pisze, że jest mu smutno, coś je boli, ktoś je krzywdzi albo jest w niebezpieczeństwie, odpowiedz ciepło i zachęć, żeby od razu powiedziało o tym rodzicowi lub innemu zaufanemu dorosłemu; nie kontynuuj wtedy zadania.`;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Tylko POST." }, 405);

  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!apiKey) return json({ error: "Pomocnik AI nie jest jeszcze włączony. Poproś rodzica." }, 503);

  const auth = req.headers.get("authorization");
  if (!auth) return json({ error: "Brak logowania." }, 401);
  const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });

  let body: { task_id?: string; messages?: { role: string; content: string }[] };
  try { body = await req.json(); } catch { return json({ error: "Zły format." }, 400); }
  if (!body.task_id) return json({ error: "Brak zadania." }, 400);

  // RLS limits this read to the caller's family
  const { data: task } = await sb.from("dz_tasks").select("title, details, steps, source, due_date").eq("id", body.task_id).maybeSingle();
  if (!task) return json({ error: "Nie znalazłem tego zadania." }, 404);

  const history = Array.isArray(body.messages) ? body.messages : [];
  if (history.length > MAX_TURNS) return json({ error: "Ta rozmowa jest już długa. Zamknij ją i zacznij od nowa albo poproś rodzica." }, 400);
  const messages: Anthropic.Beta.BetaMessageParam[] = [];
  for (const m of history) {
    if ((m.role !== "user" && m.role !== "assistant") || typeof m.content !== "string" || !m.content.trim()) return json({ error: "Zły format rozmowy." }, 400);
    messages.push({ role: m.role, content: m.content.slice(0, MAX_CHARS) });
  }
  if (!messages.length || messages[0].role !== "user") messages.unshift({ role: "user", content: "Cześć, potrzebuję pomocy z tym zadaniem." });
  if (messages[messages.length - 1].role !== "user") return json({ error: "Napisz coś do Pomocnika." }, 400);

  const about = [
    "Zadanie dziecka: " + task.title,
    task.details ? "Szczegóły: " + task.details : "",
    Array.isArray(task.steps) && task.steps.length ? "Kroki: " + task.steps.join("; ") : "",
    task.source === "eduvulcan" ? "To zadanie z dziennika szkolnego." : "",
  ].filter(Boolean).join("\n");

  const client = new Anthropic({ apiKey });
  try {
    const res = await client.beta.messages.create({
      model: "claude-opus-5-5",
      max_tokens: 2000,
      output_config: { effort: "low" },
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      system: [{ type: "text", text: SYSTEM }, { type: "text", text: about }],
      messages,
    });
    if (res.stop_reason === "refusal") return json({ reply: "Tego nie mogę omówić. Wróćmy do zadania albo poproś o pomoc rodzica." });
    const reply = res.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("\n").trim();
    return json({ reply: reply || "Spróbuj napisać to jeszcze raz innymi słowami." });
  } catch (e) {
    console.error("dz-pomocnik", e);
    if (e instanceof Anthropic.RateLimitError) return json({ error: "Pomocnik ma teraz dużo pracy. Spróbuj za chwilę." }, 429);
    return json({ error: "Pomocnik chwilowo nie odpowiada. Spróbuj za chwilę albo poproś rodzica." }, 502);
  }
});
