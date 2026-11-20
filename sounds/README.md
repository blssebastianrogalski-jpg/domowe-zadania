# Dźwięki powiadomień (wybiera je rodzic)

Te same pliki są w aplikacji Android (`android/app/src/main/res/raw/dz_*.wav`) i tutaj, żeby rodzic mógł ich
posłuchać w panelu przed wyborem. Wszystkie zostały wygenerowane w tym repozytorium, nic nie jest pobrane z internetu.

| Plik | Co to jest | Jak powstał |
|---|---|---|
| `glos_wykonaj.wav` | krótki dzwonek + głos „Wykonaj zadanie!” | `generuj_glos.py`: syntezator mowy eSpeak NG 1.51 (głos `pl`), obróbka ffmpeg, dzwonek z sinusów |
| `glos_czas.wav` | krótki dzwonek + głos „Hej! Czas na zadanie!” | jak wyżej |
| `dzwonek.wav`, `gong.wav`, `budzik.wav`, `melodia.wav`, `syrena.wav` | proste sygnały | `generuj.py`: czyste sinusy liczone w Pythonie |

Licencja: dźwięki są wygenerowane od zera skryptami z tego repozytorium. eSpeak NG jest na licencji GPL-3.0; licencja
dotyczy programu, a nie dźwięku, który on wytwarza (tak rozumiemy GPL, to nie jest porada prawna). Głos eSpeak NG
brzmi syntetycznie („robotycznie”); żeby zmienić tekst albo głos, edytuj `generuj_glos.py` i uruchom go ponownie
(wymaga `espeak-ng` i `ffmpeg`).
