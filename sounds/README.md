# Dźwięki powiadomień (wybiera je rodzic)

Te same pliki są w aplikacji Android (`android/app/src/main/res/raw/dz_*.mp3`) i tutaj, żeby rodzic mógł ich
posłuchać w panelu przed wyborem.

Wszystkie wygenerowane 20.11.2026 w ElevenLabs na koncie właściciela projektu, potem wyrównana głośność (ffmpeg loudnorm, −14 LUFS, mono 128 kb/s).

| Plik | Co to jest | Jak powstał |
|---|---|---|
| `glos_wykonaj.mp3` | „Hej! Wykonaj zadanie!” | ElevenLabs text-to-speech, model eleven_v4, głos „Lilly – Warm & Friendly Storyteller” (biblioteka głosów) |
| `glos_czas.mp3` | „Hej! Czas na zadanie!” | ElevenLabs text-to-speech, model eleven_v4, głos „Patryk – Friendly, Warm & Persuasive” (biblioteka głosów) |
| `dzwonek.mp3`, `gong.mp3`, `budzik.mp3`, `melodia.mp3`, `syrena.mp3` | sygnały | ElevenLabs sound effects, model eleven_text_to_sound_v3, z opisu słownego |

Licencja: warunki korzystania z wygenerowanych plików określa regulamin ElevenLabs i plan konta, na którym powstały
(użycie komercyjne zależy od planu). Przed użyciem poza domem warto to sprawdzić w ustawieniach konta ElevenLabs.
