-- DNS TXT records (DKIM keys, long SPF/verification strings) routinely exceed 255 characters.
-- With VARCHAR(255) the import's per-asset sub-transaction failed and the record was silently
-- dropped. No index or view depends on the column, so the type change is a cheap metadata rewrite.
ALTER TABLE asset ALTER COLUMN identifier TYPE TEXT;
