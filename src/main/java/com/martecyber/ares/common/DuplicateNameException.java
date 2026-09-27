package com.martecyber.ares.common;

/** A create/save-as-template operation was rejected because a sibling entity already has this
 *  exact name — surfaced as 409 with a machine-readable {@code code} property (see {@link
 *  GlobalExceptionHandler#duplicateName}) so the frontend can offer an explicit "overwrite the
 *  existing one, or pick a different name" choice instead of just showing the raw error. Callers
 *  that support in-place overwrite accept an {@code overwrite} flag that, when true, skips this
 *  check entirely and updates the existing row instead of throwing. */
public class DuplicateNameException extends RuntimeException {
    public DuplicateNameException(String message) {
        super(message);
    }
}
