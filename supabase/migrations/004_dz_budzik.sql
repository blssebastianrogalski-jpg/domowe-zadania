-- Budzik dla dzieci. Rodzic ustawia (godzina, dni, dla kogo, wł./wył., wyzwanie).
-- Dziecko może go tylko wyłączyć, gdy dzwoni, i tylko po rozwiązaniu wyzwania sprawdzanego na serwerze.

create table if not exists public.dz_alarms (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.dz_families(id) on delete cascade,
  member_id uuid not null references public.dz_members(id) on delete cascade,
  at time not null,
  -- days of week like JS getDay() and extract(dow): 0 = niedziela, 1 = poniedziałek ... 6 = sobota
  days smallint[] not null default '{1,2,3,4,5}' check (days <@ '{0,1,2,3,4,5,6}'::smallint[] and cardinality(days) between 1 and 7),
  enabled boolean not null default true,
  -- 'math' = przykłady z matematyki, 'code' = przepisz losowy kod. New kinds: extend the check and dz_alarm_question.
  challenge text not null default 'math' check (challenge in ('math','code')),
  level smallint not null default 1 check (level between 1 and 3),
  problems smallint not null default 3 check (problems between 1 and 10),
  -- Written only by dz_alarm_answer (security definer), never by the child directly
  dismissed_day date,
  dismissed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists dz_alarms_family_idx on public.dz_alarms(family_id);

-- One row per alarm per day it rang: the questions with answers, progress, and who was notified.
-- RLS on, no policies: the browser never sees the answers, only the functions below and the service role.
create table if not exists public.dz_alarm_rings (
  alarm_id uuid not null references public.dz_alarms(id) on delete cascade,
  day date not null,
  questions jsonb not null default '[]'::jsonb,
  solved int not null default 0,
  wrong int not null default 0,
  parent_notified_at timestamptz,
  dismissed_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (alarm_id, day)
);

alter table public.dz_alarms enable row level security;
alter table public.dz_alarm_rings enable row level security;

-- Everyone in the family reads alarms; only a parent adds, changes or deletes them
drop policy if exists dz_alarms_read on public.dz_alarms;
create policy dz_alarms_read on public.dz_alarms for select to authenticated using (family_id = public.dz_my_family());
drop policy if exists dz_alarms_write on public.dz_alarms;
create policy dz_alarms_write on public.dz_alarms for all to authenticated
  using (family_id = public.dz_my_family() and public.dz_is_parent())
  with check (family_id = public.dz_my_family() and public.dz_is_parent());

-- The family's local day if the alarm is ringing right now (from its time for 60 minutes, until dismissed), else null
create or replace function public.dz_alarm_ring_day(a public.dz_alarms) returns date
language sql stable security definer set search_path = public as $$
  select case when a.enabled
      and extract(dow from l)::int = any(a.days)
      and l::time >= a.at and l::time - a.at < interval '60 minutes'
      and a.dismissed_day is distinct from l::date
    then l::date end
  from (select now() at time zone coalesce((select tz from dz_families where id = a.family_id), 'Europe/Warsaw') as l) x
$$;

create or replace function public.dz_rand(lo int, hi int) returns int
language sql volatile as $$ select lo + floor(random() * (hi - lo + 1))::int $$;

-- One question with its answer: {"q": "7 + 5", "a": "12"}
create or replace function public.dz_alarm_question(p_kind text, p_level int) returns jsonb
language plpgsql volatile set search_path = public as $$
declare a int; b int; c text := ''; abc text := 'ACEFHJKLMNPRTUWXY34679'; i int;
begin
  if p_kind = 'code' then
    for i in 1..(4 + p_level * 2) loop c := c || substr(abc, dz_rand(1, length(abc)), 1); end loop;
    return jsonb_build_object('q', c, 'a', c);
  end if;
  if p_level = 1 then
    if random() < .5 then a := dz_rand(3, 12); b := dz_rand(3, 9); return jsonb_build_object('q', a || ' + ' || b, 'a', (a + b)::text);
    else a := dz_rand(8, 20); b := dz_rand(2, a - 3); return jsonb_build_object('q', a || ' − ' || b, 'a', (a - b)::text); end if;
  elsif p_level = 2 then
    if random() < .5 then a := dz_rand(14, 68); b := dz_rand(11, 99 - a); return jsonb_build_object('q', a || ' + ' || b, 'a', (a + b)::text);
    else a := dz_rand(31, 99); b := dz_rand(12, a - 6); return jsonb_build_object('q', a || ' − ' || b, 'a', (a - b)::text); end if;
  else
    a := dz_rand(3, 9); b := dz_rand(3, 9);
    if random() < .6 then return jsonb_build_object('q', a || ' · ' || b, 'a', (a * b)::text);
    else return jsonb_build_object('q', (a * b) || ' : ' || a, 'a', b::text); end if;
  end if;
end $$;

-- Checks the caller may dismiss this alarm and returns today's ring row (created with questions on first use)
create or replace function public.dz_alarm_ring(p_alarm uuid, out a public.dz_alarms, out r public.dz_alarm_rings, out d date)
language plpgsql security definer set search_path = public as $$
declare me uuid; qs jsonb := '[]'::jsonb; i int;
begin
  select * into a from dz_alarms where id = p_alarm and family_id = dz_my_family();
  if a.id is null then raise exception 'Nie ma takiego budzika'; end if;
  select member_id into me from dz_devices where user_id = auth.uid();
  if me is distinct from a.member_id and not dz_is_parent() then raise exception 'To nie Twój budzik'; end if;
  d := dz_alarm_ring_day(a);
  if d is null then return; end if;
  insert into dz_alarm_rings(alarm_id, day) values (a.id, d) on conflict do nothing;
  select * into r from dz_alarm_rings where alarm_id = a.id and day = d for update;
  -- New set on first ring, after the parent changed the number of questions, or when the parent moved
  -- an already dismissed alarm to later the same day
  if jsonb_array_length(r.questions) < a.problems or r.solved >= a.problems then
    for i in 1..a.problems loop qs := qs || dz_alarm_question(a.challenge, a.level); end loop;
    update dz_alarm_rings set questions = qs, solved = 0, dismissed_at = null where alarm_id = a.id and day = d returning * into r;
  end if;
end $$;

-- What the ringing screen shows: the current question (never the answer) and progress
create or replace function public.dz_alarm_challenge(p_alarm uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare x record;
begin
  select * into x from dz_alarm_ring(p_alarm);
  if x.d is null then return jsonb_build_object('ringing', false); end if;
  return jsonb_build_object('ringing', true, 'kind', (x.a).challenge, 'solved', (x.r).solved, 'total', (x.a).problems,
    'q', (x.r).questions -> (x.r).solved ->> 'q');
end $$;

-- One answer. Right: next question, or the alarm is off after the last one. Wrong: a new question in its place.
create or replace function public.dz_alarm_answer(p_alarm uuid, p_answer text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare x record; ok boolean; n int;
begin
  select * into x from dz_alarm_ring(p_alarm);
  if x.d is null then return jsonb_build_object('ringing', false, 'done', true); end if;
  ok := upper(replace(coalesce(p_answer, ''), ' ', '')) = upper((x.r).questions -> (x.r).solved ->> 'a');
  if ok then
    n := (x.r).solved + 1;
    if n >= (x.a).problems then
      update dz_alarm_rings set solved = n, dismissed_at = now() where alarm_id = (x.a).id and day = x.d;
      update dz_alarms set dismissed_day = x.d, dismissed_at = now() where id = (x.a).id;
      return jsonb_build_object('ringing', false, 'done', true, 'correct', true);
    end if;
    update dz_alarm_rings set solved = n where alarm_id = (x.a).id and day = x.d;
  else
    n := (x.r).solved;
    update dz_alarm_rings set wrong = wrong + 1,
      questions = jsonb_set(questions, array[n::text], dz_alarm_question((x.a).challenge, (x.a).level))
      where alarm_id = (x.a).id and day = x.d;
  end if;
  select * into x from dz_alarm_ring(p_alarm);
  return jsonb_build_object('ringing', true, 'done', false, 'correct', ok, 'kind', (x.a).challenge,
    'solved', (x.r).solved, 'total', (x.a).problems, 'q', (x.r).questions -> (x.r).solved ->> 'q');
end $$;

revoke all on function public.dz_alarm_ring_day(public.dz_alarms) from public, anon, authenticated;
revoke all on function public.dz_alarm_ring(uuid) from public, anon, authenticated;
revoke all on function public.dz_alarm_question(text, int) from public, anon, authenticated;
revoke all on function public.dz_rand(int, int) from public, anon, authenticated;
revoke all on function public.dz_alarm_challenge(uuid) from public, anon;
revoke all on function public.dz_alarm_answer(uuid, text) from public, anon;
grant execute on function public.dz_alarm_challenge(uuid) to authenticated;
grant execute on function public.dz_alarm_answer(uuid, text) to authenticated;

do $$ begin
  begin alter publication supabase_realtime add table public.dz_alarms; exception when duplicate_object then null; end;
end $$;
