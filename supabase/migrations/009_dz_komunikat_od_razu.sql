-- Komunikat w kilka sekund: zaraz po wysłaniu baza sama woła dz-reminders tylko dla tego komunikatu,
-- więc powiadomienie nie czeka na minutowego crona. Budzik DZ dowiaduje się o nim przez dz-czekaj (długie zapytanie).

create extension if not exists pg_net;

create or replace function public.dz_message_ring_now() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform net.http_post(
    url := 'https://jfirrtnbpshtijdqankt.supabase.co/functions/v1/dz-reminders',
    headers := jsonb_build_object('content-type', 'application/json',
      'x-cron-secret', (select value from public.dz_config where key = 'cron_secret')),
    body := jsonb_build_object('ring', new.id)
  );
  return new;
end $$;
revoke all on function public.dz_message_ring_now() from public, anon, authenticated;

drop trigger if exists dz_message_ring_now on public.dz_alarms;
create trigger dz_message_ring_now after insert on public.dz_alarms
  for each row when (new.message is not null) execute function public.dz_message_ring_now();
