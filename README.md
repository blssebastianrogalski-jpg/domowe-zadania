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

## Pomocnik AI
- „Potrzebuję pomocy” daje wybór: pomoc rodzica (jak dotąd) albo Pomocnik AI. Pomocnik prowadzi dziecko pytaniami i nie podaje gotowej odpowiedzi.
- Funkcja `supabase/functions/dz-pomocnik` (wdrażana z wyłączoną weryfikacją JWT; zadanie czyta tokenem dziecka przez RLS, więc obce zadania są niewidoczne) woła Claude API. Klucz `ANTHROPIC_API_KEY` ustawia się w Supabase → Edge Functions → Secrets; bez niego Pomocnik odpowiada, że nie jest włączony.
- Limity na rozmowę: 30 wiadomości, 800 znaków na wiadomość.

## Budzik
- Rodzic w widoku „Dla rodzica” → „⏰ Budziki” ustawia godzinę, dni, dziecko i sposób wyłączania (matematyka łatwa / do 100 / tabliczka mnożenia albo przepisanie losowego kodu, 2–5 zadań). Może budzik wyłączyć, zmienić i usunąć.
- Dziecko nie może zmienić ani usunąć budzika (RLS: zapis w `dz_alarms` tylko dla rodzica). Wyłącza go tylko funkcja `dz_alarm_answer`, która sprawdza odpowiedzi na serwerze; odpowiedzi leżą w `dz_alarm_rings` bez dostępu z przeglądarki. Zła odpowiedź daje nowe zadanie.
- `dz-reminders` wysyła push do dziecka co minutę od ustawionej godziny, aż zadania będą rozwiązane (najdłużej 60 min), a po 15 minutach raz powiadamia rodziców.
- Po otwarciu aplikacji: pełny ekran, głośny sygnał (2,6/3,2 kHz, pełna skala, generowany WAV w elemencie `<audio>`, bo na iPhonie media grają mimo przełącznika wyciszenia, a Web Audio nie), wibracje na Androidzie, ekran nie gaśnie. Głośność = głośność multimediów telefonu; przeglądarka nie podgłośni ponad nią.
- „🌙 Tryb nocny” u dziecka: przed snem stuknięcie odblokowuje dźwięk (cisza w pętli), ekran jest czarny z zegarem i nie gaśnie (Wake Lock). O czasie budzik dzwoni w aplikacji bez kolejnego stuknięcia, także na wyciszonym telefonie. Wymaga otwartej aplikacji i ładowarki.
- „🔊 Sprawdź głośność” gra sygnał przez 3 sekundy.
- Ograniczenia PWA: przy zablokowanym telefonie słychać tylko zwykły dźwięk powiadomienia, raz na minutę. Głośny alarm gra dopiero po otwarciu aplikacji (przeglądarka wymaga stuknięcia). Tryb „Nie przeszkadzać” / skupienie wycisza powiadomienia.
- Migracja: `supabase/migrations/004_dz_budzik.sql`.
