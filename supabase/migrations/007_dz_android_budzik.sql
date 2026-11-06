-- Budzik na Androidzie: aplikacja natywna (folder android/) czyta budziki dziecka bez logowania,
-- przez tajny klucz urządzenia. Klucz tworzy aplikacja webowa dziecka przyciskiem „📲 Budzik na Androida”.

create table if not exists public.dz_alarm_links (
  secret text primary key,
  family_id uuid not null references public.dz_families(id) on delete cascade,
  member_id uuid not null references public.dz_members(id) on delete cascade,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz
);
-- RLS on, no policies: only the functions below touch it
alter table public.dz_alarm_links enable row level security;

-- Called by the signed-in child's device: a new secret for this member (old ones keep working until the member is deleted)
create or replace function public.dz_alarm_link() returns text
language plpgsql security definer set search_path = public as $$
declare d dz_devices; s text;
begin
  select * into d from dz_devices where user_id = auth.uid();
  if d.member_id is null then raise exception 'Najpierw wybierz, kim jesteś.'; end if;
  s := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  insert into dz_alarm_links(secret, family_id, member_id) values (s, d.family_id, d.member_id);
  return s;
end $$;
revoke all on function public.dz_alarm_link() from public, anon;
grant execute on function public.dz_alarm_link() to authenticated;

-- Called by the Android app with the secret: the child's name, the family's time zone and its alarms.
-- Times and days only, nothing else from the family.
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
    'alarms', coalesce((select jsonb_agg(jsonb_build_object('id', id, 'at', to_char(at, 'HH24:MI'), 'days', days,
        'enabled', enabled, 'dismissed_day', dismissed_day, 'challenge', challenge) order by at)
      from dz_alarms where member_id = l.member_id), '[]'::jsonb));
end $$;
revoke all on function public.dz_alarm_feed(text) from public;
grant execute on function public.dz_alarm_feed(text) to anon, authenticated;
