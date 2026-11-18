# Domowe Zadania na Androida (dawniej Budzik DZ)

Natywna aplikacja Android dla całej rodziny, do instalacji z pliku APK (bez sklepu). Powstała, bo na telefonach Xiaomi
(MIUI / HyperOS, także Redmi i POCO) wersja w Chrome jest usypiana i przypomnienia z Web Push nie dochodzą jak należy.

Co jest w środku:

- **Cała aplikacja webowa w WebView** (`MainActivity`): ta sama strona z GitHub Pages, więc wszystkie funkcje i wygląd
  są takie same i każda zmiana strony od razu jest w aplikacji, bez nowego APK. WebView to nie Chrome, więc limity
  Family Link na Chrome i usypianie Chrome przez Xiaomi jej nie dotyczą.
- **Natywne powiadomienia bez Chrome i bez Web Push** (`Inbox`, `WaitService`): przypomnienia o zadaniach (z przyciskami
  „Zrobione” i „Za 15 min”), podsumowania na dziś, a rodzicowi „prosi o pomoc”, „zrobione” i „nie wyłączył budzika”.
  Działa dla dziecka i dla rodzica.
- **Prawdziwy budzik** jak dotąd (`RingService`, `RingActivity`): kanał alarmu, pełny ekran nad ekranem blokady,
  zadania do wyłączenia na ekranie budzika, komunikaty od rodzica.
- **Ekran „Ustawienia telefonu”** (`SetupActivity`): co Android pozwala (powiadomienia, pełny ekran, dokładne alarmy,
  optymalizacja baterii, każde z przyciskiem do właściwego miejsca) i kroki Xiaomi: Autostart, oszczędzanie baterii
  „Bez ograniczeń”, „Wyświetlaj na ekranie blokady” i wyskakujące okna w tle, kanały powiadomień, kłódka w ostatnich
  aplikacjach. Xiaomi nie udostępnia stanu tych przełączników, więc każdy krok odhacza się ręcznie. Do tego „Próba z
  serwera” (całą drogą jak prawdziwe przypomnienie) i próbny budzik.

## Jak działają powiadomienia

1. Aplikacja webowa w WebView sama łączy telefon z osobą, która go używa (`window.DZAndroid`, funkcja `dz_alarm_link`),
   także po zmianie osoby. Dawny przycisk w Chrome (`dzbudzik://link?s=…`) też działa.
2. `dz-reminders` (co minutę) dla osoby z aplikacją wkłada powiadomienie do skrzynki `dz_native_inbox`
   (migracja `supabase/migrations/011_dz_android_powiadomienia.sql`). Budzików nie wkłada, aplikacja dzwoni nimi sama.
3. `WaitService` trzyma otwarte długie zapytanie do `dz-czekaj`, które odpowiada, gdy w skrzynce pojawi się coś nowego
   albo zmienią się budziki. Aplikacja pobiera wtedy skrzynkę (`dz_native_inbox_read`) i pokazuje powiadomienia,
   zwykle w kilka sekund. Zapas: sprawdzanie co 5 minut (`AlarmManager`), po restarcie i aktualizacji.
4. Dopóki aplikacja odzywa się do serwera (ostatnie 30 minut), `dz-reminders` nie wysyła tej osobie Web Push, żeby nic
   nie przychodziło podwójnie. Gdy telefon uśpi aplikację na dłużej, Web Push (jeśli był włączony w Chrome) wraca sam.
5. Powiadomienie, które dotarło z opóźnieniem ponad 15 minut (telefon spał), jest pomijane.

Bez Firebase (FCM): nie trzeba zakładać projektu w Google ani trzymać klucza konta usługi. Kosztem jest stałe małe
powiadomienie „Domowe Zadania czekają na przypomnienia” (usługa pierwszoplanowa) i trochę więcej baterii.

## Aktualizacje

- Zmiany strony (wygląd, zadania, przyciski) przychodzą same, bez nowego APK.
- Nowa wersja APK: aplikacja sprawdza najnowsze wydanie na GitHubie (przy otwarciu, najczęściej co godzinę, i w tle
  co 6 godzin) i pyta „Nowa wersja aplikacji. Zaktualizować?”. Po „Aktualizuj” sama pobiera `domowe-zadania.apk`
  i oddaje je instalatorowi Androida (`Updater.java`, PackageInstaller). Android pokazuje swoje okno potwierdzenia:
  jedno stuknięcie. Bez żadnego stuknięcia instalować może tylko Google Play albo telefon firmowy.
- Za pierwszym razem Android prosi o zgodę „Instaluj nieznane aplikacje” dla tej aplikacji. Na telefonie dziecka
  z Family Link może być to zablokowane: wtedy zgodę daje rodzic w Family Link albo aktualizuje rodzic.
- Przycisk „⬆️ Aktualizuj” i „Sprawdź aktualizację” są też w „Ustawienia telefonu”.

## Budowanie

GitHub Actions (`.github/workflows/android.yml`) buduje APK przy każdej zmianie w `android/`. Na `main` publikuje je
jako najnowsze wydanie: `domowe-zadania.apk` i ten sam plik pod starą nazwą `budzik-dz.apk` (z niej pobiera przycisk
w aplikacji webowej). Lokalnie: Android SDK, potem `gradle -p android assembleRelease`.

Podpis: sekrety repozytorium `BUDZIK_KEYSTORE_B64` (plik keystore w base64, alias `budzik`) i
`BUDZIK_KEYSTORE_PASSWORD`. Nazwa pakietu zostaje `pl.domowezadania.budzik`, więc nowa wersja instaluje się na Budzik DZ
bez odinstalowania i zachowuje jego ustawienia.

## Instalacja

1. Telefon z Budzikiem DZ: po prostu zainstaluj nowy APK z wydania (zastąpi Budzik DZ, ikona zmieni nazwę na „Domowe
   Zadania”). Nowy telefon: w Domowych Zadaniach w Chrome → Ustawienia → „📲 Aplikacja na Androida”, albo pobierz
   `domowe-zadania.apk` z najnowszego wydania. Telefon dziecka z Family Link: najpierw w Family Link zezwól na aplikacje
   z nieznanych źródeł.
2. Otwórz aplikację, dołącz do rodziny kodem i wybierz, kim jesteś (aplikacja ma osobne logowanie niż Chrome).
3. Za pierwszym razem otworzy się „Ustawienia telefonu”: przejdź wszystkie kroki z ⚠️, potem „🔔 Próba z serwera”
   z zablokowanym ekranem.
4. Stara ikona Domowych Zadań z Chrome może zostać, ale lepiej ją usunąć albo wyłączyć jej powiadomienia, żeby nie
   mylić dzieci.
