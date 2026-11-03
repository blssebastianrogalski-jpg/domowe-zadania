-- Rodzic dostaje powiadomienie, gdy dziecko oznaczy zadanie jako zrobione (raz na dzień wykonania).
alter table public.dz_tasks add column if not exists done_notified_day date;
-- Zadania zrobione przed wdrożeniem nie wysyłają zaległych powiadomień.
update public.dz_tasks set done_notified_day = status_day where status = 'done' and done_notified_day is null;
