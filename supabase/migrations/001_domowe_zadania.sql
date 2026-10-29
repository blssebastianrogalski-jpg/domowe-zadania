-- Domowe Zadania: schema, RLS, RPCs. All objects prefixed dz_ so they can live beside other apps.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.dz_families (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 60),
  invite_code text not null unique,
  tz text not null default 'Europe/Warsaw',
  created_at timestamptz not null default now()
);

create table if not exists public.dz_members (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.dz_families(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 30),
  role text not null default 'child' check (role in ('parent','child')),
  created_at timestamptz not null default now()
);

-- One row per signed-in device (anonymous auth user)
create table if not exists public.dz_devices (
  user_id uuid primary key references auth.users(id) on delete cascade,
  family_id uuid not null references public.dz_families(id) on delete cascade,
  member_id uuid references public.dz_members(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists public.dz_tasks (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.dz_families(id) on delete cascade,
  member_id uuid not null references public.dz_members(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 80),
  steps jsonb not null default '[]'::jsonb,
  due time,
  remind_at time,
  repeat text not null default 'daily' check (repeat in ('daily','once')),
  status text not null default 'todo' check (status in ('todo','doing','snoozed','help','done')),
  status_day date,
  snooze_until timestamptz,
  steps_done jsonb not null default '[]'::jsonb,
  steps_day date,
  snooze_notified_at timestamptz,
  remind_notified_day date,
  help_notified_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists dz_tasks_family_idx on public.dz_tasks(family_id);

create table if not exists public.dz_tally (
  family_id uuid not null references public.dz_families(id) on delete cascade,
  day date not null,
  count int not null default 0 check (count >= 0),
  primary key (family_id, day)
);

create table if not exists public.dz_push_subs (
  endpoint text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  family_id uuid not null references public.dz_families(id) on delete cascade,
  p256dh text not null,
  auth text not null,
  created_at timestamptz not null default now()
);

-- Server-only settings (VAPID keys, cron secret). RLS on, no policies: only the service role reads it.
create table if not exists public.dz_config (
  key text primary key,
  value text not null
);

-- ---------- helpers ----------
create or replace function public.dz_my_family() returns uuid
language sql stable security definer set search_path = public as $$
  select family_id from dz_devices where user_id = auth.uid()
$$;

create or replace function public.dz_is_parent() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from dz_devices d join dz_members m on m.id = d.member_id
    where d.user_id = auth.uid() and m.role = 'parent'
  )
$$;

create or replace function public.dz_today(fid uuid) returns date
language sql stable security definer set search_path = public as $$
  select (now() at time zone coalesce((select tz from dz_families where id = fid), 'Europe/Warsaw'))::date
$$;

-- ---------- RLS ----------
alter table public.dz_families enable row level security;
alter table public.dz_members enable row level security;
alter table public.dz_devices enable row level security;
alter table public.dz_tasks enable row level security;
alter table public.dz_tally enable row level security;
alter table public.dz_push_subs enable row level security;
alter table public.dz_config enable row level security;

drop policy if exists dz_families_read on public.dz_families;
create policy dz_families_read on public.dz_families for select to authenticated using (id = public.dz_my_family());

drop policy if exists dz_members_read on public.dz_members;
create policy dz_members_read on public.dz_members for select to authenticated using (family_id = public.dz_my_family());
drop policy if exists dz_members_write on public.dz_members;
create policy dz_members_write on public.dz_members for all to authenticated
  using (family_id = public.dz_my_family() and public.dz_is_parent())
  with check (family_id = public.dz_my_family() and public.dz_is_parent());

drop policy if exists dz_devices_read on public.dz_devices;
create policy dz_devices_read on public.dz_devices for select to authenticated using (user_id = auth.uid());

drop policy if exists dz_tasks_read on public.dz_tasks;
create policy dz_tasks_read on public.dz_tasks for select to authenticated using (family_id = public.dz_my_family());
drop policy if exists dz_tasks_write on public.dz_tasks;
create policy dz_tasks_write on public.dz_tasks for all to authenticated
  using (family_id = public.dz_my_family() and public.dz_is_parent())
  with check (family_id = public.dz_my_family() and public.dz_is_parent());

drop policy if exists dz_tally_read on public.dz_tally;
create policy dz_tally_read on public.dz_tally for select to authenticated using (family_id = public.dz_my_family());
drop policy if exists dz_tally_write on public.dz_tally;
create policy dz_tally_write on public.dz_tally for all to authenticated
  using (family_id = public.dz_my_family() and public.dz_is_parent())
  with check (family_id = public.dz_my_family() and public.dz_is_parent());

drop policy if exists dz_push_own on public.dz_push_subs;
create policy dz_push_own on public.dz_push_subs for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid() and family_id = public.dz_my_family());

-- ---------- RPCs ----------
create or replace function public.dz_create_family(p_family_name text, p_parent_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare fid uuid; mid uuid; code text;
begin
  if auth.uid() is null then raise exception 'Brak logowania'; end if;
  if exists (select 1 from dz_devices where user_id = auth.uid()) then raise exception 'To urządzenie należy już do rodziny'; end if;
  loop
    code := upper(substr(translate(encode(extensions.gen_random_bytes(8), 'base64'), '+/=0O1IL', ''), 1, 6));
    exit when char_length(code) = 6 and not exists (select 1 from dz_families where invite_code = code);
  end loop;
  insert into dz_families(name, invite_code) values (trim(p_family_name), code) returning id into fid;
  insert into dz_members(family_id, name, role) values (fid, trim(p_parent_name), 'parent') returning id into mid;
  insert into dz_devices(user_id, family_id, member_id) values (auth.uid(), fid, mid);
  return fid;
end $$;

create or replace function public.dz_join_family(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare fid uuid;
begin
  if auth.uid() is null then raise exception 'Brak logowania'; end if;
  select id into fid from dz_families where invite_code = upper(trim(p_code));
  if fid is null then raise exception 'Nie znam takiego kodu rodziny'; end if;
  insert into dz_devices(user_id, family_id) values (auth.uid(), fid)
    on conflict (user_id) do update set family_id = excluded.family_id, member_id = null;
  return fid;
end $$;

create or replace function public.dz_pick_member(p_member uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from dz_members where id = p_member and family_id = dz_my_family()) then
    raise exception 'Nie ma takiej osoby w rodzinie';
  end if;
  update dz_devices set member_id = p_member where user_id = auth.uid();
end $$;

-- Executor actions: anyone in the family may change status of a task, nothing else
create or replace function public.dz_task_action(p_task uuid, p_action text, p_snooze timestamptz default null, p_steps jsonb default null) returns void
language plpgsql security definer set search_path = public as $$
declare t dz_tasks; d date;
begin
  select * into t from dz_tasks where id = p_task and family_id = dz_my_family();
  if t.id is null then raise exception 'Nie ma takiego zadania'; end if;
  d := dz_today(t.family_id);
  if p_action = 'steps' then
    update dz_tasks set steps_done = coalesce(p_steps, '[]'::jsonb), steps_day = d, updated_at = now() where id = p_task;
  elsif p_action = 'snoozed' then
    if p_snooze is null or p_snooze <= now() then raise exception 'Wybierz przyszłą godzinę'; end if;
    update dz_tasks set status = 'snoozed', status_day = d, snooze_until = p_snooze, snooze_notified_at = null, updated_at = now() where id = p_task;
  elsif p_action in ('todo','doing','help','done') then
    update dz_tasks set status = p_action, status_day = d, snooze_until = null,
      help_notified_at = case when p_action = 'help' then null else help_notified_at end,
      updated_at = now() where id = p_task;
  else
    raise exception 'Nieznana akcja';
  end if;
end $$;

revoke all on function public.dz_create_family(text, text) from public, anon;
revoke all on function public.dz_join_family(text) from public, anon;
revoke all on function public.dz_pick_member(uuid) from public, anon;
revoke all on function public.dz_task_action(uuid, text, timestamptz, jsonb) from public, anon;
grant execute on function public.dz_create_family(text, text) to authenticated;
grant execute on function public.dz_join_family(text) to authenticated;
grant execute on function public.dz_pick_member(uuid) to authenticated;
grant execute on function public.dz_task_action(uuid, text, timestamptz, jsonb) to authenticated;

-- Realtime for live updates between phones
do $$ begin
  begin alter publication supabase_realtime add table public.dz_tasks; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.dz_tally; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.dz_members; exception when duplicate_object then null; end;
end $$;
