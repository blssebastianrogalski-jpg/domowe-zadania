# Budzik DZ (Android)

Mała aplikacja Android z prawdziwym budzikiem dla Domowych Zadań. Aplikacja webowa (PWA) na zablokowanym
telefonie może tylko pokazać powiadomienie; ta aplikacja dzwoni jak budzik w Zegarze:

- dźwięk budzika w pętli na kanale alarmu, więc gra także przy wyciszonym telefonie i w trybie Nie przeszkadzać
  (jeśli tryb dopuszcza alarmy, tak jest domyślnie); głośność alarmu idzie na maksimum na czas dzwonienia,
- budzik na cały ekran nad ekranem blokady, wibracje,
- dźwięk do wyboru w aplikacji: dźwięk telefonu albo własna piosenka (domyślnie dźwięk alarmu telefonu),
- wyłącza się dopiero, gdy dziecko rozwiąże zadania w aplikacji Domowe Zadania (serwer zapisuje wyłączenie,
  aplikacja sprawdza to co 15 s), gdy rodzic wyłączy budzik, albo po 60 minutach.

## Jak działa

- Budziki ustawia rodzic jak dotąd w aplikacji webowej. Aplikacja Android pobiera budziki dziecka funkcją
  `dz_alarm_feed` (migracja `supabase/migrations/007_dz_android_budzik.sql`) z tajnym kluczem urządzenia:
  co 15 minut oraz po każdym dzwonku, restarcie i zmianie czasu. Następny budzik ustawia w systemie (`setAlarmClock`).
- Klucz tworzy aplikacja webowa dziecka przyciskiem „📲 Budzik na Androida” (`dz_alarm_link`), który otwiera tę
  aplikację linkiem `dzbudzik://link?s=…`. Gdy aplikacji nie ma, Chrome pobiera APK z najnowszego wydania.
- Tuż przed dzwonkiem aplikacja pyta serwer, czy budzik nadal jest aktualny. Bez internetu dzwoni według ostatnio
  pobranych danych.

## Budowanie

GitHub Actions (`.github/workflows/android.yml`) buduje APK przy każdej zmianie w `android/` i na `main` publikuje je
jako najnowsze wydanie (`budzik-dz.apk`). Lokalnie: Android SDK, potem `gradle -p android assembleRelease`.

Podpis: workflow bierze klucz z sekretów repozytorium `BUDZIK_KEYSTORE_B64` (plik keystore w base64, alias `budzik`)
i `BUDZIK_KEYSTORE_PASSWORD`. Bez nich podpisuje jednorazowym kluczem, a wtedy nową wersję można zainstalować dopiero
po odinstalowaniu starej (budziki wracają po ponownym „📲 Budzik na Androida”).

## Instalacja na telefonie dziecka

1. W Domowych Zadaniach (Chrome) dziecka: część „Mój budzik” → „📲 Budzik na Androida”. Pobierze się `budzik-dz.apk`.
2. Otwórz pobrany plik i zezwól Chrome na instalowanie aplikacji z nieznanych źródeł.
3. Po instalacji stuknij „📲 Budzik na Androida” jeszcze raz: aplikacja się połączy i pokaże budziki.
4. W aplikacji Budzik DZ stuknij przyciski z ⚠️ (powiadomienia, pełny ekran, oszczędzanie baterii) i zrób
   „🔔 Próbny budzik za 10 sekund” z zablokowanym ekranem.
