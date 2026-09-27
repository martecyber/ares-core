SET search_path TO ares, public;

-- Ships a ready-to-use example so every instance has at least one working "report a finding by
-- email" template out of the box (KB → Templates → Email), demonstrating the finding.* variables
-- (AQL-shaped, mirrors DOCX report templates), {{#name}}...{{/name}} repeat blocks for affections
-- and per-reference-type sections, and the per-template severity label/color override
-- (priority_colors) that replaced the old platform-wide admin setting.
INSERT INTO ares.email_template (name, subject_template, html_content, priority_colors, created_at, updated_at)
VALUES (
    'Finding report (example)',
    '[{{finding.severityLabel}}] {{finding.code}} — {{finding.title}}',
    '<div style="font-family: Arial, sans-serif; max-width: 640px; margin: 0 auto;">

  <div style="background: {{finding.severityColor}}; color: #ffffff; padding: 14px 20px; border-radius: 6px 6px 0 0;">
    <span style="font-size: 12px; opacity: 0.85; letter-spacing: 0.5px;">{{finding.code}}</span><br>
    <span style="font-size: 18px; font-weight: bold;">{{finding.title}}</span><br>
    <span style="font-size: 13px; font-weight: bold;">SEVERITY: {{finding.severityLabel}}</span>
  </div>

  <div style="border: 1px solid #e2e2e2; border-top: none; padding: 20px; border-radius: 0 0 6px 6px;">

    <table style="width: 100%; border-collapse: collapse; margin-bottom: 20px; font-size: 13px;">
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold; width: 140px;">Code</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{finding.code}}</td>
      </tr>
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">Severity</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">
          <span style="background: {{finding.severityColor}}; color: #fff; padding: 2px 8px; border-radius: 3px; font-weight: bold;">{{finding.severityLabel}}</span>
        </td>
      </tr>
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">Status</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{finding.status}}</td>
      </tr>
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">Project</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{project.name}}</td>
      </tr>

      {{#finding.cve}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">CVE</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{title}}{{#url}} — <a href="{{url}}">{{url}}</a>{{/url}}</td>
      </tr>
      {{/finding.cve}}

      {{#finding.cwe}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">CWE</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{title}}</td>
      </tr>
      {{/finding.cwe}}

      {{#finding.capec}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">CAPEC</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{title}}</td>
      </tr>
      {{/finding.capec}}

      {{#finding.attack}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">MITRE ATT&amp;CK</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{title}}</td>
      </tr>
      {{/finding.attack}}

      {{#finding.owasp}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">OWASP Top 10</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;">{{title}}</td>
      </tr>
      {{/finding.owasp}}

      {{#finding.url}}
      <tr>
        <td style="padding: 6px 10px; background: #f5f5f5; font-weight: bold;">Link</td>
        <td style="padding: 6px 10px; border-bottom: 1px solid #eee;"><a href="{{url}}">{{title}}</a></td>
      </tr>
      {{/finding.url}}
    </table>

    <h3 style="font-size: 14px; color: #333; border-bottom: 2px solid {{finding.severityColor}}; padding-bottom: 4px;">Description</h3>
    <p style="font-size: 13px; color: #444; line-height: 1.5;">{{finding.fields.description}}</p>

    {{#finding.affections}}
    <h3 style="font-size: 14px; color: #333; border-bottom: 2px solid {{finding.severityColor}}; padding-bottom: 4px; margin-top: 20px;">Affections</h3>
    <div style="margin-bottom: 14px; font-size: 13px; color: #444;">
      <div style="font-weight: bold;">[{{code}}] - {{title}}</div>
      <div style="color: #666; margin: 2px 0;">
        Affected assets: {{#affects}}{{identifier}}, {{/affects}}
      </div>
      <div>{{description}}</div>
    </div>
    {{/finding.affections}}

    <h3 style="font-size: 14px; color: #333; border-bottom: 2px solid {{finding.severityColor}}; padding-bottom: 4px; margin-top: 20px;">Impact</h3>
    <p style="font-size: 13px; color: #444; line-height: 1.5;">{{finding.fields.impact}}</p>

    <h3 style="font-size: 14px; color: #333; border-bottom: 2px solid {{finding.severityColor}}; padding-bottom: 4px; margin-top: 20px;">Remediation</h3>
    <p style="font-size: 13px; color: #444; line-height: 1.5;">{{finding.fields.remediation}}</p>

  </div>
</div>',
    '{"critical":{"label":"CRITICAL","color":"#ef4444"},"high":{"label":"HIGH","color":"#f97316"},"medium":{"label":"MEDIUM","color":"#ca8a04"},"low":{"label":"LOW","color":"#16a34a"},"info":{"label":"INFORMATIONAL","color":"#3b82f6"}}'::jsonb,
    NOW(), NOW()
);
