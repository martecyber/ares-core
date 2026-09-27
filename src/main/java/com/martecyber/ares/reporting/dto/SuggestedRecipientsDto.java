package com.martecyber.ares.reporting.dto;

import com.martecyber.ares.projects.rules.ReportRecipientRuleService.ResolvedRecipient;
import com.martecyber.ares.projects.rules.ReportRecipientRuleService.SuggestedRecipients;

import java.util.List;

/** Recipients suggested by "email_recipients" Rules of Engagement for a finding's "Report by
 *  email" dialog — {@code ruleId}/{@code ruleNote} let the frontend mark each chip as
 *  rule-sourced (locked, amber) and explain which rule added it. */
public record SuggestedRecipientsDto(
    List<RecipientDto> to,
    List<RecipientDto> cc,
    List<RecipientDto> bcc
) {
    public record RecipientDto(String email, String label, Long ruleId, String ruleNote) {
        static RecipientDto from(ResolvedRecipient r) {
            return new RecipientDto(r.email(), r.label(), r.ruleId(), r.ruleNote());
        }
    }

    public static SuggestedRecipientsDto from(SuggestedRecipients r) {
        return new SuggestedRecipientsDto(
            r.to().stream().map(RecipientDto::from).toList(),
            r.cc().stream().map(RecipientDto::from).toList(),
            r.bcc().stream().map(RecipientDto::from).toList()
        );
    }
}
