-- Komunikat do dziecka: rodzic wpisuje wiadomość, a u dziecka od razu dzwoni budzik z tą wiadomością.
-- Komunikat to jednorazowy budzik w dz_alarms (dzwoni tylko w dniu ring_on, od godziny wysłania przez 60 minut),
-- więc dzwoni, przychodzi na zablokowany ekran i wyłącza się dokładnie tak jak zwykły budzik (dz_alarm_answer).

alter table public.dz_alarms add column if not exists message text check (char_length(message) between 1 and 300);
-- Set only for a message: the one day it rings. Null = an ordinary alarm on its weekdays.
alter table public.dz_alarms add column if not exists ring_on date;
-- Who sent the message (shown to the child as "📢 Tata pisze:")
alter table public.dz_alarms add column if not exists sent_by uuid references public.dz_members(id) on delete set null;

-- Same as in 004, plus: a message rings only on its day
create or replace function public.dz_alarm_ring_day(a public.dz_alarms) returns date
language sql stable security definer set search_path = public as $$
  select case when a.enabled
      and extract(dow from l)::int = any(a.days)
      and (a.ring_on is null or a.ring_on = l::date)
      and l::time >= a.at and l::time - a.at < interval '60 minutes'
      and a.dismissed_day is distinct from l::date
    then l::date end
  from (select now() at time zone coalesce((select tz from dz_families where id = a.family_id), 'Europe/Warsaw') as l) x
$$;
revoke all on function public.dz_alarm_ring_day(public.dz_alarms) from public, anon, authenticated;

-- Called by a parent: sends the message to a child now. The time and day come from the family's time zone,
-- so the alarm rings at once whatever clock the parent's phone has. Messages older than 30 days are cleared.
create or replace function public.dz_send_message(p_member uuid, p_message text, p_challenge text default 'math',
  p_level int default 1, p_problems int default 3) returns uuid
language plpgsql security definer set search_path = public as $$
declare fam uuid := dz_my_family(); me uuid; l timestamp; new_id uuid; msg text := btrim(coalesce(p_message, ''));
begin
  if fam is null or not dz_is_parent() then raise exception 'Komunikat może wysłać tylko rodzic.'; end if;
  if not exists (select 1 from dz_members where id = p_member and family_id = fam) then raise exception 'Nie ma takiego domownika.'; end if;
  if msg = '' then raise exception 'Wpisz treść komunikatu.'; end if;
  select member_id into me from dz_devices where user_id = auth.uid();
  l := now() at time zone coalesce((select tz from dz_families where id = fam), 'Europe/Warsaw');
  delete from dz_alarms where family_id = fam and ring_on is not null and ring_on < l::date - 30;
  insert into dz_alarms(family_id, member_id, at, days, enabled, challenge, level, problems, message, ring_on, sent_by)
    values (fam, p_member, date_trunc('minute', l)::time, array[extract(dow from l)::smallint], true,
      coalesce(p_challenge, 'math'), coalesce(p_level, 1), coalesce(p_problems, 3), left(msg, 300), l::date, me)
    returning dz_alarms.id into new_id;
  return new_id;
end $$;
revoke all on function public.dz_send_message(uuid, text, text, int, int) from public, anon;
grant execute on function public.dz_send_message(uuid, text, text, int, int) to authenticated;

-- Android app feed (007) with the message fields, so Budzik DZ rings for messages too and shows their text
create or replace function public.dz_alarm_feed(p_secret text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return null; end if;
  update dz_alarm_links set last_seen_at = now() where secret = p_secret;
  return jsonb_build_object(
    'name', (select name from dz_members where id = l.member_id),
    'tz', coalesce((select tz from dz_families where id = l.family_id), 'Europe/Warsaw'),
    'today', (now() at time zone coalesce((select tz from dz_families where id = l.family_id), 'Europe/Warsaw'))::date,
    'alarms', coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'at', to_char(a.at, 'HH24:MI'), 'days', a.days,
        'enabled', a.enabled, 'dismissed_day', a.dismissed_day, 'challenge', a.challenge,
        'message', a.message, 'ring_on', a.ring_on, 'from', (select name from dz_members where id = a.sent_by)) order by a.at)
      from dz_alarms a where a.member_id = l.member_id
        and (a.ring_on is null or a.ring_on >= (now() at time zone coalesce((select tz from dz_families where id = l.family_id), 'Europe/Warsaw'))::date - 1)), '[]'::jsonb));
end $$;
revoke all on function public.dz_alarm_feed(text) from public;
grant execute on function public.dz_alarm_feed(text) to anon, authenticated;
