-- Profile-scoped realtime sync tables.
-- account_sync_state remains as a backward-compatible snapshot/backup path.

create table if not exists public.profile_settings (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text not null,
  settings jsonb not null default '{}'::jsonb,
  revision bigint not null default 0,
  device_id text,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  primary key (user_id, profile_id)
);

create table if not exists public.profile_addons (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text not null,
  addon_id text not null,
  payload jsonb not null default '{}'::jsonb,
  sort_index integer not null default 0,
  is_inherited boolean not null default false,
  inherited_from_profile_id text,
  revision bigint not null default 0,
  device_id text,
  deleted_at timestamptz,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  primary key (user_id, profile_id, addon_id)
);

create table if not exists public.profile_catalogs (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text not null,
  catalog_id text not null,
  payload jsonb not null default '{}'::jsonb,
  sort_index integer not null default 0,
  revision bigint not null default 0,
  device_id text,
  deleted_at timestamptz,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  primary key (user_id, profile_id, catalog_id)
);

create table if not exists public.profile_iptv_state (
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text not null,
  state jsonb not null default '{}'::jsonb,
  revision bigint not null default 0,
  device_id text,
  updated_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  primary key (user_id, profile_id)
);

create table if not exists public.profile_sync_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id text,
  scope text not null,
  entity_id text,
  op text not null check (op in ('upsert','delete','snapshot')),
  revision bigint not null default 0,
  device_id text,
  created_at timestamptz not null default now()
);

alter table public.watch_history
  add column if not exists profile_id text;

alter table public.watched_movies
  add column if not exists profile_id text;

alter table public.watched_episodes
  add column if not exists profile_id text;

update public.watch_history
set profile_id = 'default'
where profile_id is null;

update public.watched_movies
set profile_id = 'default'
where profile_id is null;

update public.watched_episodes
set profile_id = 'default'
where profile_id is null;

create index if not exists profile_settings_user_updated_idx
  on public.profile_settings (user_id, updated_at desc);
create index if not exists profile_addons_user_profile_updated_idx
  on public.profile_addons (user_id, profile_id, updated_at desc);
create index if not exists profile_catalogs_user_profile_updated_idx
  on public.profile_catalogs (user_id, profile_id, updated_at desc);
create index if not exists profile_iptv_state_user_updated_idx
  on public.profile_iptv_state (user_id, updated_at desc);
create index if not exists profile_sync_events_user_created_idx
  on public.profile_sync_events (user_id, created_at desc);
create index if not exists watch_history_user_profile_updated_idx
  on public.watch_history (user_id, profile_id, updated_at desc);
create index if not exists watched_movies_user_profile_idx
  on public.watched_movies (user_id, profile_id, tmdb_id);
create index if not exists watched_episodes_user_profile_idx
  on public.watched_episodes (user_id, profile_id, tmdb_id, season, episode);

do $$
declare
  sync_table_name text;
begin
  foreach sync_table_name in array array[
    'profile_settings',
    'profile_addons',
    'profile_catalogs',
    'profile_iptv_state',
    'profile_sync_events'
  ]
  loop
    execute format('alter table public.%I enable row level security', sync_table_name);
    execute format('alter table public.%I force row level security', sync_table_name);

    if not exists (
      select 1
      from pg_policies
      where schemaname = 'public'
        and tablename = sync_table_name
        and policyname = 'users_manage_own_' || sync_table_name
    ) then
      execute format(
        'create policy %I on public.%I for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)',
        'users_manage_own_' || sync_table_name,
        sync_table_name
      );
    end if;
  end loop;
end $$;

do $$
declare
  legacy_constraint record;
begin
  for legacy_constraint in
    select c.conname
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where n.nspname = 'public'
      and t.relname = 'watched_movies'
      and c.contype in ('u', 'p')
      and (
        select array_agg(a.attname order by a.attname)
        from unnest(c.conkey) key(attnum)
        join pg_attribute a on a.attrelid = t.oid and a.attnum = key.attnum
      ) = array['tmdb_id','user_id']
  loop
    execute format('alter table public.watched_movies drop constraint %I', legacy_constraint.conname);
  end loop;

  for legacy_constraint in
    select c.conname
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where n.nspname = 'public'
      and t.relname = 'watched_episodes'
      and c.contype in ('u', 'p')
      and (
        select array_agg(a.attname order by a.attname)
        from unnest(c.conkey) key(attnum)
        join pg_attribute a on a.attrelid = t.oid and a.attnum = key.attnum
      ) = array['episode','season','tmdb_id','user_id']
  loop
    execute format('alter table public.watched_episodes drop constraint %I', legacy_constraint.conname);
  end loop;
end $$;

create unique index if not exists watched_movies_user_profile_tmdb_uidx
  on public.watched_movies (user_id, profile_id, tmdb_id);

create unique index if not exists watched_episodes_user_profile_episode_uidx
  on public.watched_episodes (user_id, profile_id, tmdb_id, season, episode);

create or replace function public.bump_profile_sync_revision()
returns trigger
language plpgsql
as $$
begin
  new.revision = coalesce(old.revision, 0) + 1;
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists profile_settings_bump_revision on public.profile_settings;
create trigger profile_settings_bump_revision
before update on public.profile_settings
for each row execute function public.bump_profile_sync_revision();

drop trigger if exists profile_addons_bump_revision on public.profile_addons;
create trigger profile_addons_bump_revision
before update on public.profile_addons
for each row execute function public.bump_profile_sync_revision();

drop trigger if exists profile_catalogs_bump_revision on public.profile_catalogs;
create trigger profile_catalogs_bump_revision
before update on public.profile_catalogs
for each row execute function public.bump_profile_sync_revision();

drop trigger if exists profile_iptv_state_bump_revision on public.profile_iptv_state;
create trigger profile_iptv_state_bump_revision
before update on public.profile_iptv_state
for each row execute function public.bump_profile_sync_revision();

do $$
declare
  sync_table_name text;
begin
  foreach sync_table_name in array array[
    'account_sync_state',
    'watch_history',
    'watched_movies',
    'watched_episodes',
    'watchlist',
    'profile_settings',
    'profile_addons',
    'profile_catalogs',
    'profile_iptv_state',
    'profile_sync_events'
  ]
  loop
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = sync_table_name
      ) then
      execute format('alter publication supabase_realtime add table public.%I', sync_table_name);
    end if;
  end loop;
end $$;
