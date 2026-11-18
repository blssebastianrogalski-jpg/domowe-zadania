-- Powiadomienia w aplikacji Android (Domowe Zadania na Androida, folder android/), bez Chrome i bez Web Push.
-- Na telefonach Xiaomi (MIUI/HyperOS) Chrome w tle bywa usypiany i przypomnienia z Web Push nie dochodzą.
-- Funkcja dz-reminders, zamiast (albo oprócz) Web Push, wkłada powiadomienie do skrzynki dz_native_inbox.
-- Aplikacja Android czeka na nie długim zapytaniem (dz-czekaj) i odbiera je funkcją dz_native_inbox_read
-- z tajnym kluczem urządzenia (dz_alarm_links, migracja 007). Działa dla dziecka i dla rodzica.

-- notify = ta instalacja odbiera powiadomienia sama (ustawia to pierwsze dz_native_inbox_read).
-- Dopóki aplikacja odzywa się do serwera (last_seen_at w ostatnich 30 min), dz-reminders nie wysyła tej osobie
-- Web Push, żeby nie dzwoniło podwójnie. Gdy Xiaomi uśpi aplikację, po 30 min wraca Web Push jako zapas.
alter table public.dz_alarm_links add column if not exists notify boolean not null default false;

create table if not exists public.dz_native_inbox (
  id bigserial primary key,
  member_id uuid not null references public.dz_members(id) on delete cascade,
  created_at timestamptz not null default now(),
  payload jsonb not null
);
create index if not exists dz_native_inbox_member on public.dz_native_inbox(member_id, id);
-- RLS on, no policies: only the service role (dz-reminders, dz-czekaj) and the functions below touch it
alter table public.dz_native_inbox enable row level security;

-- Called by the Android app with its secret: new notifications after p_after (the last id it has shown).
-- p_after 0 = a freshly linked app: only the last 10 minutes, so it does not replay the whole day.
create or replace function public.dz_native_inbox_read(p_secret text, p_after bigint default 0) returns jsonb
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links; items jsonb; last_id bigint;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return null; end if;
  update dz_alarm_links set last_seen_at = now(), notify = true where secret = p_secret;
  -- Housekeeping: notifications older than 2 days are of no use to anyone
  delete from dz_native_inbox where member_id = l.member_id and created_at < now() - interval '2 days';
  select coalesce(jsonb_agg(jsonb_build_object('id', id, 'at', created_at, 'p', payload) order by id), '[]'::jsonb), max(id)
    into items, last_id
    from (select * from dz_native_inbox
          where member_id = l.member_id and id > coalesce(p_after, 0)
            and created_at > now() - case when coalesce(p_after, 0) = 0 then interval '10 minutes' else interval '2 hours' end
          order by id limit 30) x;
  return jsonb_build_object('items', items,
    'last', coalesce(last_id, (select max(id) from dz_native_inbox where member_id = l.member_id), coalesce(p_after, 0)));
end $$;
revoke all on function public.dz_native_inbox_read(text, bigint) from public;
grant execute on function public.dz_native_inbox_read(text, bigint) to anon, authenticated;

-- "Próba z serwera" in the app: puts a test notification in this device's inbox, so the whole way
-- (serwer -> dz-czekaj -> aplikacja -> powiadomienie na zablokowanym ekranie) can be checked in one tap.
create or replace function public.dz_native_test(p_secret text) returns boolean
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return false; end if;
  -- At most one test a minute per person
  if exists (select 1 from dz_native_inbox where member_id = l.member_id and payload->>'kind' = 'test' and created_at > now() - interval '1 minute') then
    return true;
  end if;
  insert into dz_native_inbox(member_id, payload) values (l.member_id, jsonb_build_object(
    'title', '🔔 Próba z serwera', 'body', 'Działa! Tak przyjdzie przypomnienie o zadaniu, także przy zablokowanym ekranie.',
    'kind', 'test', 'task', 'test'));
  return true;
end $$;
revoke all on function public.dz_native_test(text) from public;
grant execute on function public.dz_native_test(text) to anon, authenticated;
