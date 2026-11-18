-- 013: raport z telefonu (aplikacja Android) o powiadomieniach.
-- Aplikacja po pokazaniu powiadomień z dz_native_inbox mówi serwerowi, które pokazała i jak był ustawiony telefon
-- (tryb cichy, Nie przeszkadzać, głośność, czy kanał „Przypomnienia o zadaniach” ma dźwięk, czy ruszył dzwonek).
-- Dzięki temu „telefon milczy” da się sprawdzić w bazie zamiast zgadywać. Starsze wersje aplikacji tego nie wołają.

alter table public.dz_native_inbox add column if not exists shown_at timestamptz;
alter table public.dz_native_inbox add column if not exists shown_info jsonb;

-- Ostatni stan telefonu przy każdej instalacji (także gdy pominęła stare powiadomienia)
alter table public.dz_alarm_links add column if not exists phone_state jsonb;
alter table public.dz_alarm_links add column if not exists phone_state_at timestamptz;

create or replace function public.dz_native_inbox_seen(p_secret text, p_ids bigint[], p_info jsonb) returns boolean
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then return false; end if;
  -- Bez ograniczenia rozmiaru nikt nie powinien tu nic wpisywać
  if p_info is not null and length(p_info::text) > 4000 then p_info := null; end if;
  update dz_alarm_links set phone_state = p_info, phone_state_at = now() where secret = p_secret;
  update dz_native_inbox set shown_at = now(), shown_info = p_info
    where member_id = l.member_id and id = any(coalesce(p_ids, '{}')) and shown_at is null;
  return true;
end $$;
revoke all on function public.dz_native_inbox_seen(text, bigint[], jsonb) from public;
grant execute on function public.dz_native_inbox_seen(text, bigint[], jsonb) to anon, authenticated;
