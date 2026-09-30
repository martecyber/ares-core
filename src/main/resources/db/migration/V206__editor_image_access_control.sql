-- Editor images were served publicly (see V180's own comment) — an opaque sequential id, no auth
-- at all, deliberately, since a plain <img src> can't send an Authorization header. That's fine
-- against guessing but not against enumeration (ids are small sequential integers) or against
-- someone with the URL who never had legitimate access. Real fix: an unguessable public token
-- instead of the sequential id, plus optional organization_id/project_id scope captured at
-- upload time (best-effort — the editor is used from platform-wide forms too, where both stay
-- NULL and the image is gated to MSSP staff instead) enforced by the GET endpoint, which now
-- requires authentication. The frontend switches from a raw <img src> to an authenticated fetch
-- + blob URL (see MarkdownView.vue/utils/markdown.ts) to keep working under real auth; the Word
-- report generator resolves these images in-process instead of over HTTP (see
-- ReportGenerationService), so it never needed the public endpoint in the first place.

ALTER TABLE ares.editor_image
    ADD COLUMN token           UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN organization_id BIGINT REFERENCES ares.organization(id) ON DELETE SET NULL,
    ADD COLUMN project_id      BIGINT REFERENCES ares.project(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_editor_image_token ON ares.editor_image (token);
