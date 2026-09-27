package com.martecyber.ares.aql.compile;

import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.registry.EntityAqlRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a parsed query and confirms every referenced field name actually exists on the target
 * registry, without compiling or executing anything — lets {@code /api/v1/aql/validate} give live
 * feedback on a query as the user types without paying for a real DB round trip. Every registered
 * field resolves against Postgres today (the AQL-wide initiative's federated 2-phase Mongo
 * fallback, which this class used to also plan for, was retired once CVE/CWE/CAPEC/OWASP/ATT&amp;CK
 * all migrated — see {@code PostgresSpecificationCompiler}'s own history).
 */
public class AqlPlanner {

    private AqlPlanner() { }

    public static <T> void validate(AqlNode node, EntityAqlRegistry<T> registry) {
        List<String> fieldNames = new ArrayList<>();
        collectFieldNames(node, fieldNames);
        for (String name : fieldNames) {
            registry.requireField(name);
        }
    }

    private static void collectFieldNames(AqlNode node, List<String> out) {
        switch (node) {
            case AqlNode.And and -> and.operands().forEach(n -> collectFieldNames(n, out));
            case AqlNode.Or or -> or.operands().forEach(n -> collectFieldNames(n, out));
            case AqlNode.Not not -> collectFieldNames(not.operand(), out);
            case AqlNode.Comparison cmp -> out.add(cmp.field());
            case AqlNode.BareTerm ignored -> { }
        }
    }
}
