-- user_settings is used as a cloud-sync fallback mirror when account_sync_state
-- cannot be written, so realtime must publish it too.
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (
      select 1
      from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = 'user_settings'
    ) then
    alter publication supabase_realtime add table public.user_settings;
  end if;
end $$;
