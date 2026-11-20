-- 014: dźwięk i głośność powiadomień na telefonie dziecka ustawia rodzic (aplikacja Android).
-- Rodzic w panelu wybiera dziecku dźwięk z listy i głośność. Aplikacja Android na telefonie dziecka sama gra ten
-- dźwięk przy każdym przypomnieniu, wołaniu i podsumowaniu, na strumieniu alarmu i z głośnością rodzica, zamiast
-- dźwięku kanału powiadomień, który dziecko może wyciszyć w ustawieniach telefonu. Dziecko nie ma tej opcji w aplikacji.
-- Aplikacja co kilka minut melduje też stan telefonu (tryb cichy, Nie przeszkadzać, wyłączone powiadomienia),
-- a rodzic widzi go w panelu. Zakazać dziecku wyciszenia w systemie się nie da: to tylko wykrywamy i pokazujemy.
-- Kolumny phone_state* są też w 013; tu powtórzone, żeby kolejność wklejania nie miała znaczenia.

alter table public.dz_members add column if not exists notify_sound text
  check (notify_sound in ('glos_wykonaj', 'glos_czas', 'dzwonek', 'gong', 'budzik', 'melodia', 'syrena', 'telefon'));
-- Procent maksymalnej głośności alarmu w telefonie
alter table public.dz_members add column if not exists notify_volume smallint
  check (notify_volume between 10 and 100);

alter table public.dz_alarm_links add column if not exists phone_state jsonb;
alter table public.dz_alarm_links add column if not exists phone_state_at timestamptz;

-- Rodzic ustawia dźwięk dziecka (p_sound null = jak dawniej, decyduje telefon). Zmiana trafia do skrzynki
-- telefonu (dz_native_inbox), więc dz-czekaj budzi aplikację i ustawienie działa po kilku sekundach.
create or replace function public.dz_set_sound(p_member uuid, p_sound text, p_volume int) returns boolean
language plpgsql security definer set search_path = public as $$
declare fam uuid := dz_my_family(); vol int := greatest(10, least(100, coalesce(p_volume, 80)));
begin
  if fam is null or not dz_is_parent() then raise exception 'Dźwięk ustawia tylko rodzic.'; end if;
  if p_sound is not null and p_sound not in ('glos_wykonaj', 'glos_czas', 'dzwonek', 'gong', 'budzik', 'melodia', 'syrena', 'telefon') then
    raise exception 'Nieznany dźwięk';
  end if;
  update dz_members set notify_sound = p_sound, notify_volume = case when p_sound is null then null else vol end
    where id = p_member and family_id = fam;
  if not found then return false; end if;
  insert into dz_native_inbox(member_id, payload) values (p_member, jsonb_build_object(
    'kind', 'settings', 'task', 'settings', 'sound', p_sound, 'volume', case when p_sound is null then null else vol end,
    -- Starsza aplikacja (bez tej funkcji) pokaże to jako zwykłe powiadomienie
    'title', '🔊 Rodzic ustawił dźwięk powiadomień', 'body', 'Zaktualizuj aplikację Domowe Zadania, żeby działał.'));
  return true;
end $$;
revoke all on function public.dz_set_sound(uuid, text, int) from public, anon;
grant execute on function public.dz_set_sound(uuid, text, int) to authenticated;

-- „Zagraj na telefonie dziecka”: telefon raz gra ustawiony dźwięk z ustawioną głośnością. Najwyżej raz na 20 s.
create or replace function public.dz_sound_test(p_member uuid) returns boolean
language plpgsql security definer set search_path = public as $$
declare fam uuid := dz_my_family(); m dz_members;
begin
  if fam is null or not dz_is_parent() then raise exception 'Tylko rodzic.'; end if;
  select * into m from dz_members where id = p_member and family_id = fam;
  if m.id is null then return false; end if;
  if exists (select 1 from dz_native_inbox where member_id = p_member and payload->>'kind' = 'settings'
             and (payload->>'play')::boolean and created_at > now() - interval '20 seconds') then return true; end if;
  insert into dz_native_inbox(member_id, payload) values (p_member, jsonb_build_object(
    'kind', 'settings', 'task', 'settings', 'sound', m.notify_sound, 'volume', m.notify_volume, 'play', true,
    'title', '🔊 Próba dźwięku od rodzica', 'body', 'Zaktualizuj aplikację Domowe Zadania, żeby działał dźwięk od rodzica.'));
  return true;
end $$;
revoke all on function public.dz_sound_test(uuid) from public, anon;
grant execute on function public.dz_sound_test(uuid) to authenticated;

-- Aplikacja Android z tajnym kluczem urządzenia, co kilka minut: zapisuje stan telefonu i odbiera ustawienie dźwięku.
create or replace function public.dz_native_hello(p_secret text, p_state jsonb) returns jsonb
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links; m dz_members;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return null; end if;
  if p_state is not null and length(p_state::text) > 4000 then p_state := null; end if;
  update dz_alarm_links set phone_state = coalesce(p_state, phone_state), phone_state_at = now(), last_seen_at = now()
    where secret = p_secret;
  select * into m from dz_members where id = l.member_id;
  return jsonb_build_object('sound', m.notify_sound, 'volume', m.notify_volume);
end $$;
revoke all on function public.dz_native_hello(text, jsonb) from public;
grant execute on function public.dz_native_hello(text, jsonb) to anon, authenticated;

-- Panel rodzica: ostatni stan telefonu każdego domownika (najświeższa instalacja aplikacji Android).
create or replace function public.dz_phone_status() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare fam uuid := dz_my_family();
begin
  if fam is null or not dz_is_parent() then return '[]'::jsonb; end if;
  return coalesce((select jsonb_agg(x) from (
    select distinct on (l.member_id) l.member_id, l.phone_state, l.phone_state_at, l.last_seen_at
    from dz_alarm_links l join dz_members mm on mm.id = l.member_id
    where mm.family_id = fam
    order by l.member_id, greatest(l.phone_state_at, l.last_seen_at) desc nulls last) x), '[]'::jsonb);
end $$;
revoke all on function public.dz_phone_status() from public, anon;
grant execute on function public.dz_phone_status() to authenticated;
