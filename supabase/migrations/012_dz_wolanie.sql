-- Zawołaj dziecko: rodzic stuka kafelek, wybiera dziecko (albo wszystkie dzieci) i u dziecka od razu przychodzi
-- powiadomienie „📣 Tata cię woła”. Dziecko odpowiada jednym stuknięciem („Idę!” albo „Za 5 minut”), a rodzic,
-- który wołał, dostaje odpowiedź. Dziecko może też po prostu zamknąć powiadomienie, gdy od razu idzie.
-- Jak dzieci nazywają rodzica („Tata”, „Mama”) ustawia rodzic przy kafelku: kolumna dz_members.call_as.

alter table public.dz_members add column if not exists call_as text check (char_length(call_as) between 1 and 30);

create table if not exists public.dz_calls (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.dz_families(id) on delete cascade,
  from_member uuid references public.dz_members(id) on delete set null,
  to_member uuid not null references public.dz_members(id) on delete cascade,
  note text check (char_length(note) <= 120),
  created_at timestamptz not null default now(),
  -- coming = „Idę!”, soon = „Za 5 minut”, seen = zamknięte powiadomienie bez odpowiedzi (tylko aplikacja Android)
  answer text check (answer in ('coming', 'soon', 'seen')),
  answered_at timestamptz,
  answer_notified_at timestamptz
);
create index if not exists dz_calls_family on public.dz_calls(family_id, created_at desc);
alter table public.dz_calls enable row level security;
drop policy if exists dz_calls_read on public.dz_calls;
create policy dz_calls_read on public.dz_calls for select to authenticated using (family_id = public.dz_my_family());
-- Writes only through the functions below
do $$ begin
  begin alter publication supabase_realtime add table public.dz_calls; exception when duplicate_object then null; end;
end $$;

-- Called by a parent: calls the given children now. Returns how many were called.
create or replace function public.dz_call(p_members uuid[], p_note text default null) returns int
language plpgsql security definer set search_path = public as $$
declare fam uuid := dz_my_family(); me uuid; n int; txt text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if fam is null or not dz_is_parent() then raise exception 'Zawołać może tylko rodzic.'; end if;
  select member_id into me from dz_devices where user_id = auth.uid();
  delete from dz_calls where family_id = fam and created_at < now() - interval '7 days';
  insert into dz_calls(family_id, from_member, to_member, note)
    select fam, me, m.id, left(txt, 120) from dz_members m
    where m.family_id = fam and m.id = any(coalesce(p_members, '{}'))
      -- a double tap does not call twice
      and not exists (select 1 from dz_calls c where c.to_member = m.id and c.from_member is not distinct from me
                      and c.answer is null and c.created_at > now() - interval '5 seconds');
  get diagnostics n = row_count;
  return n;
end $$;
revoke all on function public.dz_call(uuid[], text) from public, anon;
grant execute on function public.dz_call(uuid[], text) to authenticated;

-- Shared by both ways of answering. A real answer replaces "seen"; "seen" never replaces an answer.
create or replace function public.dz_call_set_answer(p_call uuid, p_member uuid, p_answer text) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_answer is null or p_answer not in ('coming', 'soon', 'seen') then raise exception 'Nieznana odpowiedź'; end if;
  update dz_calls set answer = p_answer, answered_at = now(), answer_notified_at = null
    where id = p_call and to_member = p_member and created_at > now() - interval '1 day'
      and (answer is null or (p_answer <> 'seen' and answer <> p_answer));
  return found;
end $$;
revoke all on function public.dz_call_set_answer(uuid, uuid, text) from public, anon, authenticated;

-- The child answers in the web app (or after tapping a button of a web notification)
create or replace function public.dz_call_answer(p_call uuid, p_answer text) returns boolean
language plpgsql security definer set search_path = public as $$
declare me uuid;
begin
  select member_id into me from dz_devices where user_id = auth.uid();
  if me is null then return false; end if;
  return dz_call_set_answer(p_call, me, p_answer);
end $$;
revoke all on function public.dz_call_answer(uuid, text) from public, anon;
grant execute on function public.dz_call_answer(uuid, text) to authenticated;

-- The child answers straight from the Android notification, with the device secret (dz_alarm_links, 007)
create or replace function public.dz_call_answer_link(p_secret text, p_call uuid, p_answer text) returns boolean
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return false; end if;
  return dz_call_set_answer(p_call, l.member_id, p_answer);
end $$;
revoke all on function public.dz_call_answer_link(text, uuid, text) from public;
grant execute on function public.dz_call_answer_link(text, uuid, text) to anon, authenticated;

-- At once, without waiting for the minute cron: dz-reminders sends the call to the child, and later the
-- child's answer to the parent who called (same way as a message, migration 009)
create extension if not exists pg_net;
create or replace function public.dz_call_notify() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform net.http_post(
    url := 'https://jfirrtnbpshtijdqankt.supabase.co/functions/v1/dz-reminders',
    headers := jsonb_build_object('content-type', 'application/json',
      'x-cron-secret', (select value from public.dz_config where key = 'cron_secret')),
    body := jsonb_build_object('call', new.id)
  );
  return new;
end $$;
revoke all on function public.dz_call_notify() from public, anon, authenticated;

drop trigger if exists dz_call_new on public.dz_calls;
create trigger dz_call_new after insert on public.dz_calls for each row execute function public.dz_call_notify();
drop trigger if exists dz_call_answered on public.dz_calls;
create trigger dz_call_answered after update of answer on public.dz_calls for each row
  when (new.answer in ('coming', 'soon') and new.answer is distinct from old.answer) execute function public.dz_call_notify();
