SET search_path TO ares, public;

-- Research Boards: the intermediate area between Detections and Findings where analysts
-- investigate detections (status "under_investigation", see V190) before deciding whether an
-- asset is really affected. See the Research Boards plan for the full feature design.

CREATE TABLE research_board (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT      NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title                VARCHAR(200) NOT NULL,
    notes                TEXT,
    lead_user_id         BIGINT      REFERENCES "user"(id),
    status               VARCHAR(10) NOT NULL DEFAULT 'active',   -- 'active' | 'archived'
    verdict              VARCHAR(20),                              -- 'affected' | 'not_affected', set on archive
    result_affection_id  BIGINT      REFERENCES affection(id),     -- set when verdict='affected'
    archived_at          TIMESTAMPTZ,
    created_by_user_id   BIGINT      REFERENCES "user"(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_research_board_project ON research_board(project_id);
CREATE INDEX ix_research_board_project_status ON research_board(project_id, status);

-- Assigned analysts — N per board, self-assign/self-remove; lead (or MSSP_ADMIN) can add/remove
-- anyone. Same shape as project_member, minus the role column (a board has one lead, tracked on
-- research_board.lead_user_id, and otherwise-undifferentiated members).
CREATE TABLE research_board_user (
    board_id  BIGINT      NOT NULL REFERENCES research_board(id) ON DELETE CASCADE,
    user_id   BIGINT      NOT NULL REFERENCES "user"(id)         ON DELETE CASCADE,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (board_id, user_id)
);

-- Detection membership — kept after removal/board archive (removed_at set) so the archived tab
-- can still show which detections a resolved board covered; a detection can only be linked to one
-- *active* board at a time, enforced in ResearchBoardService, not here (this table has no
-- uniqueness constraint beyond the natural key below).
CREATE TABLE research_board_detection (
    board_id     BIGINT      NOT NULL REFERENCES research_board(id) ON DELETE CASCADE,
    detection_id BIGINT      NOT NULL REFERENCES detection(id)      ON DELETE CASCADE,
    added_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    removed_at   TIMESTAMPTZ,
    PRIMARY KEY (board_id, detection_id)
);
CREATE INDEX ix_research_board_detection_detection ON research_board_detection(detection_id);
