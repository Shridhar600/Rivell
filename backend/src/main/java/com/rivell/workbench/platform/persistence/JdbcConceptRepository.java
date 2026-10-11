package com.rivell.workbench.platform.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.rivell.workbench.taxonomy.Concept;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRepository;

@Repository
class JdbcConceptRepository implements ConceptRepository {

    private final JdbcClient jdbcClient;

    JdbcConceptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Concept> findAll() {
        return jdbcClient.sql("""
                select concept_key, label, parent_key, definition, aliases, contract_types, sort_order
                from legal_concept
                order by sort_order
                """)
                .query((rs, rowNum) -> new Concept(
                        ConceptKey.of(rs.getString("concept_key")),
                        rs.getString("label"),
                        rs.getString("parent_key") == null ? null : ConceptKey.of(rs.getString("parent_key")),
                        rs.getString("definition"),
                        strings(rs.getArray("aliases")),
                        strings(rs.getArray("contract_types")),
                        rs.getInt("sort_order")))
                .list();
    }

    @Override
    public int version() {
        return jdbcClient.sql("select coalesce(max(taxonomy_version), 0) from legal_concept")
                .query(Integer.class)
                .single();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
