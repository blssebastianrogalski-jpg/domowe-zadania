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

## Dziennik eduVULCAN (eksperymentalne)
- Funkcja `supabase/functions/dz-dziennik` (3× dziennie przez `pg_cron`, migracja `003_dz_dziennik.sql`) pobiera zadania domowe i sprawdziany na 14 dni i zakłada z nich jednorazowe karty dziecka.
- Rodzic łączy dziennik w widoku „Dla rodzica”, wklejając dane ze strony `eduvulcan.pl/api/ap`. Hasło nie trafia na serwer: funkcja rejestruje się w eduVULCAN jako kolejny telefon (klucz RSA w tabeli `dz_school_links`, dostęp tylko dla roli serwisowej).
- To nieoficjalne API aplikacji mobilnej (za iris, dampud/vulcan, hebece). Może przestać działać po zmianach VULCAN; błąd widać w panelu rodzica.
- Funkcje `dz-reminders` i `dz-dziennik` wdrażane z wyłączoną weryfikacją JWT (same sprawdzają sekret crona albo zalogowanego rodzica).

## Wygląd
- Każdy domownik wybiera wygląd w Ustawieniach (Morski, Ciepły poranek, Nocny granat, Kolorowe klocki, Pikselowy świat, Klockowy plac, Pastelowy kotek, Wojownik ninja). Wybór zapamiętuje telefon osobno dla każdej osoby; domyślnie rodzic ma Morski, dziecko Kolorowe klocki.
- Każdy wygląd to plik w `themes/`, ładowany po `styles.css` i nadpisujący jego kolory, czcionki i kształty.
