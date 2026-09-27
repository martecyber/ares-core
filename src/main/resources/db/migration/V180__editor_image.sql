-- Images uploaded from the Markdown rich-text editor (toolbar button / paste / drag-drop) —
-- stored in S3, served back through GET /api/v1/editor-images/{id} (public, no auth, so a
-- plain <img src> works from a Markdown-rendered preview and from generated Word documents).
-- No organization_id: the editor is used across many unrelated forms (Finding fields,
-- Affection description, report fields) with no uniform "current org" context, and access
-- control here matches the avatar/logo pattern — an opaque id, gated on upload by role.
CREATE TABLE ares.editor_image (
    id           BIGSERIAL PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    bucket       VARCHAR(100) NOT NULL,
    object_key   VARCHAR(500) NOT NULL,
    uploaded_by  BIGINT REFERENCES ares."user"(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
