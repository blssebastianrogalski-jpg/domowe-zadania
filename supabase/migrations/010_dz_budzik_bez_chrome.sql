-- Wyłączanie budzika i komunikatu w samej aplikacji Budzik DZ, bez Chrome (np. gdy Family Link wstrzymał Chrome).
-- Te same zadania i to samo sprawdzanie na serwerze co dz_alarm_challenge / dz_alarm_answer (004), ale zamiast
-- logowania aplikacja podaje tajny klucz urządzenia (dz_alarm_links, 007). Klucz pozwala wyłączyć tylko budziki
-- tego dziecka, do którego jest przypisany.

-- Checks the secret belongs to this alarm's child and returns today's ring row (created with questions on first use)
create or replace function public.dz_alarm_ring_link(p_secret text, p_alarm uuid, out a public.dz_alarms, out r public.dz_alarm_rings, out d date)
language plpgsql security definer set search_path = public as $$
declare l dz_alarm_links; qs jsonb := '[]'::jsonb; i int;
begin
  select * into l from dz_alarm_links where secret = p_secret;
  if l.secret is null then raise exception 'Budzik nie jest połączony'; end if;
  select * into a from dz_alarms where id = p_alarm and member_id = l.member_id;
  if a.id is null then raise exception 'Nie ma takiego budzika'; end if;
  d := dz_alarm_ring_day(a);
  if d is null then return; end if;
  insert into dz_alarm_rings(alarm_id, day) values (a.id, d) on conflict do nothing;
  select * into r from dz_alarm_rings where alarm_id = a.id and day = d for update;
  if jsonb_array_length(r.questions) < a.problems or r.solved >= a.problems then
    for i in 1..a.problems loop qs := qs || dz_alarm_question(a.challenge, a.level); end loop;
    update dz_alarm_rings set questions = qs, solved = 0, dismissed_at = null where alarm_id = a.id and day = d returning * into r;
  end if;
end $$;

create or replace function public.dz_alarm_challenge_link(p_secret text, p_alarm uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare x record;
begin
  select * into x from dz_alarm_ring_link(p_secret, p_alarm);
  if x.d is null then return jsonb_build_object('ringing', false); end if;
  return jsonb_build_object('ringing', true, 'kind', (x.a).challenge, 'solved', (x.r).solved, 'total', (x.a).problems,
    'q', (x.r).questions -> (x.r).solved ->> 'q');
end $$;

create or replace function public.dz_alarm_answer_link(p_secret text, p_alarm uuid, p_answer text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare x record; ok boolean; n int;
begin
  select * into x from dz_alarm_ring_link(p_secret, p_alarm);
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
  select * into x from dz_alarm_ring_link(p_secret, p_alarm);
  return jsonb_build_object('ringing', true, 'done', false, 'correct', ok, 'kind', (x.a).challenge,
    'solved', (x.r).solved, 'total', (x.a).problems, 'q', (x.r).questions -> (x.r).solved ->> 'q');
end $$;

revoke all on function public.dz_alarm_ring_link(text, uuid) from public, anon, authenticated;
revoke all on function public.dz_alarm_challenge_link(text, uuid) from public;
revoke all on function public.dz_alarm_answer_link(text, uuid, text) from public;
grant execute on function public.dz_alarm_challenge_link(text, uuid) to anon, authenticated;
grant execute on function public.dz_alarm_answer_link(text, uuid, text) to anon, authenticated;
