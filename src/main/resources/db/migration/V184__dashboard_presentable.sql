-- Dashboards must be explicitly opted in (from the dashboard's own edit view) before they can be
-- added to a SOC-screen presentation's rotation — a governance gate on top of the existing
-- staff-only DashboardPresentationController, so a presentation can never surface a dashboard its
-- owner hasn't deliberately marked safe for that wider audience. Defaults to false: every existing
-- dashboard stays out of presentations until someone opts it in.
ALTER TABLE ares.dashboard ADD COLUMN presentable BOOLEAN NOT NULL DEFAULT false;
