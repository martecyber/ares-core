-- Removes the legacy pre-Workflows notification-binding system (project detection_created,
-- org sla_due_soon, platform agent-fleet-health alerts) — superseded by Workflows' TRIGGER_EVENT
-- + ACTION_NOTIFICATION. messaging_integration/messaging_integration_grant (the channels
-- themselves) are untouched — Workflows' ACTION_NOTIFICATION still sends through them.
DROP TABLE IF EXISTS ares.messaging_platform_sent;
DROP TABLE IF EXISTS ares.messaging_sla_sent;
DROP TABLE IF EXISTS ares.messaging_event_binding;
