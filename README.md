# Domowe Zadania

Rodzinna aplikacja do obowiązków domowych: jedno zadanie naraz, „Przypomnij za 10/20/30 min” bez kary, prośba o pomoc i licznik ustnych przypomnień rodzica. Działa w przeglądarce telefonu i instaluje się na ekranie głównym (PWA), z powiadomieniami push.

## Jak to działa
- **Front-end**: statyczne pliki (`index.html`, `app.js`, `styles.css`, `sw.js`), hostowane na GitHub Pages.
- **Baza i logowanie**: Supabase. Każdy telefon loguje się anonimowo i dołącza do rodziny kodem zaproszenia. Dostęp pilnują reguły RLS (`supabase/migrations/001_domowe_zadania.sql`).
- **Przypomnienia**: funkcja `supabase/functions/dz-reminders` uruchamiana co minutę przez `pg_cron` wysyła Web Push: po odłożeniu zadania, o godzinie pierwszego przypomnienia i przy prośbie o pomoc (do rodziców).

## iPhone
Push działa na iOS 16.4+ tylko po dodaniu aplikacji do ekranu początkowego (Safari → Udostępnij → Do ekranu początkowego).

## Ustawienia w Supabase
- Authentication → Sign In / Providers → włącz **Allow anonymous sign-ins**.
