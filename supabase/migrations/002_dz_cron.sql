-- Server-side secrets are generated inside the database / edge function, never typed in.
insert into public.dz_config(key, value)
values ('cron_secret', encode(extensions.gen_random_bytes(24), 'hex'))
on conflict (key) do nothing;

-- The browser needs only the VAPID public key
create or replace function public.dz_vapid_public() returns text
language sql stable security definer set search_path = public as $$
  select value from dz_config where key = 'vapid_public'
$$;
revoke all on function public.dz_vapid_public() from public, anon;
grant execute on function public.dz_vapid_public() to authenticated;

-- Call dz-reminders every minute
create extension if not exists pg_cron;
create extension if not exists pg_net;
select cron.unschedule('dz-reminders') where exists (select 1 from cron.job where jobname = 'dz-reminders');
select cron.schedule('dz-reminders', '* * * * *', $cron$
  select net.http_post(
    url := 'https://jfirrtnbpshtijdqankt.supabase.co/functions/v1/dz-reminders',
    headers := jsonb_build_object('content-type', 'application/json',
      'x-cron-secret', (select value from public.dz_config where key = 'cron_secret')),
    body := '{}'::jsonb
  );
$cron$);
