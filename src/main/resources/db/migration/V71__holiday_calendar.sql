-- Holiday calendars: small lookup tables managed by admins, opted into per-user.
-- Days are stored individually (not generated from rules) because public holidays
-- shift every year; operators just maintain a fresh list per calendar.
SET search_path TO ares, public;

CREATE TABLE holiday_calendar (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE holiday_calendar_day (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calendar_id BIGINT       NOT NULL REFERENCES holiday_calendar(id) ON DELETE CASCADE,
    day         DATE         NOT NULL,
    label       VARCHAR(200),
    CONSTRAINT uq_holiday_day UNIQUE (calendar_id, day)
);

CREATE INDEX ix_holiday_day_cal_date ON holiday_calendar_day(calendar_id, day);

-- Each user can opt into at most one calendar. NULL = no calendar assigned.
-- ON DELETE SET NULL so deleting a calendar doesn't cascade-orphan users.
ALTER TABLE "user"
    ADD COLUMN holiday_calendar_id BIGINT
        REFERENCES holiday_calendar(id) ON DELETE SET NULL;

CREATE INDEX ix_user_holiday_calendar ON "user"(holiday_calendar_id);
