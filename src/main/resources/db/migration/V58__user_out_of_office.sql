SET search_path TO ares, public;

CREATE TABLE user_out_of_office (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    start_date DATE         NOT NULL,
    end_date   DATE         NOT NULL,
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ooo_dates CHECK (end_date >= start_date)
);

CREATE INDEX ix_ooo_user  ON user_out_of_office(user_id);
CREATE INDEX ix_ooo_dates ON user_out_of_office(start_date, end_date);
