SET search_path TO ares, public;

-- Deleting an affection used to fail with an FK violation if an archived research board pointed
-- at it as its result. The board stays archived (verdict "affected" is still true history), it
-- just loses its link to the now-deleted affection.
ALTER TABLE research_board DROP CONSTRAINT research_board_result_affection_id_fkey;
ALTER TABLE research_board
    ADD CONSTRAINT research_board_result_affection_id_fkey
    FOREIGN KEY (result_affection_id) REFERENCES affection(id) ON DELETE SET NULL;
