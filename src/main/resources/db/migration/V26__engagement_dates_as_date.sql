SET search_path TO ares, public;

ALTER TABLE engagement
  ALTER COLUMN start_date TYPE DATE USING start_date::date,
  ALTER COLUMN end_date TYPE DATE USING end_date::date;
