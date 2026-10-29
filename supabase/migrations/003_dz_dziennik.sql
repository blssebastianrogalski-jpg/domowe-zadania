-- eduVULCAN: homework and tests imported as task cards.
-- The server registers itself in eduVULCAN as one more "phone" (RSA key), the parent's password never reaches us.

alter table public.dz_tasks add column if not exists due_date date;
alter table public.dz_tasks add column if not exists details text;
alter table public.dz_tasks add column if not exists source text;
alter table public.dz_tasks add column if not exists external_id text;
create unique index if not exists dz_tasks_external_idx on public.dz_tasks(family_id, external_id);

-- One link per family. Holds the device private key: RLS on, no policies, only the service role reads it.
create table if not exists public.dz_school_links (
  family_id uuid primary key references public.dz_families(id) on delete cascade,
  credential jsonb not null,
  -- [{pupil_id, name, class, rest_url, member_id}]
  accounts jsonb not null default '[]'::jsonb,
  last_sync_at timestamptz,
  last_error text,
  created_at timestamptz not null default now()
);
alter table public.dz_school_links enable row level security;

-- Sync homework and tests 3 times a day (UTC: 05:15, 13:15, 17:15)
select cron.unschedule('dz-dziennik') where exists (select 1 from cron.job where jobname = 'dz-dziennik');
select cron.schedule('dz-dziennik', '15 5,13,17 * * *', $cron$
  select net.http_post(
    url := 'https://jfirrtnbpshtijdqankt.supabase.co/functions/v1/dz-dziennik',
    headers := jsonb_build_object('content-type', 'application/json',
      'x-cron-secret', (select value from public.dz_config where key = 'cron_secret')),
    body := '{"action":"cron"}'::jsonb,
    timeout_milliseconds := 60000
  );
$cron$);
