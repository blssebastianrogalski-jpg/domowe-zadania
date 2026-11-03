-- Powtarzanie zadania w wybrane dni tygodnia (0 = niedziela ... 6 = sobota).
-- null przy repeat = 'daily' oznacza każdy dzień, więc stare zadania działają jak dotąd.
alter table public.dz_tasks add column if not exists days smallint[]
  check (days is null or (cardinality(days) between 1 and 7 and days <@ array[0,1,2,3,4,5,6]::smallint[]));
