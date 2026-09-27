package com.martecyber.ares.aql.parser;

import java.util.List;

public sealed interface AqlNode {

    record And(List<AqlNode> operands) implements AqlNode { }

    record Or(List<AqlNode> operands) implements AqlNode { }

    record Not(AqlNode operand) implements AqlNode { }

    /** field may be a dotted path, e.g. "metadata.osVersion" or "cve.kevListed". */
    record Comparison(String field, AqlOperator operator, AqlValue value) implements AqlNode { }

    /** An unscoped bare term — resolved against the target entity's default free-text field list. */
    record BareTerm(String text, boolean quoted) implements AqlNode { }
}
