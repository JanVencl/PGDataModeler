package com.datagocar.ddl;

import com.datagocar.model.Column;
import com.datagocar.model.ColumnPair;
import com.datagocar.model.DataModel;
import com.datagocar.model.FkAction;
import com.datagocar.model.Identity;
import com.datagocar.model.Position;
import com.datagocar.model.Relationship;
import com.datagocar.model.Row;
import com.datagocar.model.Table;
import com.datagocar.model.TableKind;
import com.datagocar.ops.InvalidOperationException;
import com.datagocar.ops.ModelOperation;
import com.datagocar.ops.OperationApplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupTableTest {

    private static Column col(String id, String name, String type, boolean pk, boolean unique, Identity identity) {
        return new Column(id, name, type, !pk, pk, unique, null, identity, null);
    }

    /** gender (lookup with explicit ids) and person referencing it. */
    private static DataModel model() {
        Table gender = new Table("g", "public", "gender", null,
                List.of(col("g1", "id", "smallint", true, false, Identity.ALWAYS),
                        col("g2", "code", "varchar(1)", false, true, Identity.NONE),
                        col("g3", "name", "text", false, false, Identity.NONE)),
                List.of(), List.of(), new Position(0, 0), null, TableKind.LOOKUP,
                List.of(new Row("r1", Map.of("g1", "1", "g2", "F", "g3", "Female")),
                        new Row("r2", Map.of("g1", "2", "g2", "M", "g3", "Male's"))));
        Table person = new Table("p", "public", "person", null,
                List.of(col("p1", "id", "bigint", true, false, Identity.ALWAYS),
                        new Column("p2", "gender_id", "smallint", false, false, false, null, Identity.NONE, null)),
                List.of(), List.of(), new Position(300, 0), null);
        Relationship fk = new Relationship("fk", null, "p", "g", List.of(new ColumnPair("p2", "g1")), FkAction.NO_ACTION, null);
        return new DataModel("People", List.of(gender, person), List.of(fk), List.of());
    }

    @Test
    void sqlInsertsSeedRowsAsUpsertBeforeForeignKeys() {
        String sql = new PostgresDdlGenerator().generate(model());
        assertTrue(sql.contains("INSERT INTO public.gender (id, code, name) OVERRIDING SYSTEM VALUE\nVALUES\n"
                + "    (1, 'F', 'Female'),\n    (2, 'M', 'Male''s')\n"
                + "ON CONFLICT (id) DO UPDATE SET code = EXCLUDED.code, name = EXCLUDED.name;"), sql);
        assertTrue(sql.contains("SELECT setval(pg_get_serial_sequence('public.gender', 'id'), (SELECT max(id) FROM public.gender));"));
        assertTrue(sql.indexOf("INSERT INTO") > sql.lastIndexOf("CREATE TABLE"));
        assertTrue(sql.indexOf("INSERT INTO") < sql.indexOf("FOREIGN KEY"));
        assertFalse(sql.contains("INSERT INTO public.person"));
    }

    @Test
    void liquibaseSeedChangesetsRunOnChangeWithRollback() {
        String xml = new LiquibaseXmlGenerator().generate(model(), "jan");
        assertTrue(xml.contains("<changeSet id=\"data-public.gender\" author=\"jan\" runOnChange=\"true\">"));
        assertTrue(xml.contains("<rollback><![CDATA[DELETE FROM public.gender WHERE id IN (1, 2);]]></rollback>"));
        String formatted = new LiquibaseSqlGenerator().generate(model(), "jan");
        assertTrue(formatted.contains("--changeset jan:data-public.gender runOnChange:true\nINSERT INTO public.gender"));
    }

    @Test
    void missingValuesBecomeDefaultAndGeneratedKeyFallsBackToUniqueColumn() {
        Table role = new Table("r", "public", "company_role", null,
                List.of(col("c1", "id", "integer", true, false, Identity.ALWAYS),
                        col("c2", "code", "text", false, true, Identity.NONE),
                        new Column("c3", "active", "boolean", false, false, false, "true", Identity.NONE, null)),
                List.of(), List.of(), null, null, TableKind.LOOKUP,
                List.of(new Row("a", Map.of("c2", "ADMIN", "c3", "false")), new Row("b", Map.of("c2", "USER"))));
        String insert = SeedData.insert(role);
        assertTrue(insert.contains("INSERT INTO public.company_role (code, active)\nVALUES\n    ('ADMIN', false),\n    ('USER', DEFAULT)\n"
                + "ON CONFLICT (code) DO UPDATE SET active = EXCLUDED.active;"), insert);
        assertFalse(insert.contains("OVERRIDING"));
        assertFalse(insert.contains("setval"));
    }

    @Test
    void importReadsInsertsIntoLookupRows() {
        String sql = new PostgresDdlGenerator().generate(model());
        DataModel imported = PostgresDdlImporter.parse(sql, "x").model();
        Table gender = imported.tables().stream().filter(t -> t.name().equals("gender")).findFirst().orElseThrow();
        Table person = imported.tables().stream().filter(t -> t.name().equals("person")).findFirst().orElseThrow();
        assertEquals(TableKind.LOOKUP, gender.kind());
        assertEquals(TableKind.ENTITY, person.kind());
        assertEquals(2, gender.rows().size());
        String nameId = gender.columns().get(2).id();
        assertEquals("Male's", gender.rows().get(1).value(nameId));
        assertEquals("2", gender.rows().get(1).value(gender.columns().get(0).id()));
    }

    @Test
    void importHandlesNullsDefaultsCastsAndNegativeNumbers() {
        String sql = """
                CREATE TABLE day_of_week (id int PRIMARY KEY, name text NOT NULL, short text, offset_min int);
                INSERT INTO day_of_week VALUES (1, 'Monday', NULL, -5), (2, 'Tuesday'::text, DEFAULT, +3);
                INSERT INTO day_of_week (id, name) VALUES (3, upper('wed'));
                """;
        var result = PostgresDdlImporter.parse(sql, "x");
        Table t = result.model().tables().get(0);
        assertEquals(3, t.rows().size());
        assertEquals(Map.of(t.columns().get(0).id(), "1", t.columns().get(1).id(), "Monday", t.columns().get(3).id(), "-5"),
                t.rows().get(0).values());
        assertEquals("Tuesday", t.rows().get(1).value(t.columns().get(1).id()));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("expressions")), result.warnings().toString());
    }

    @Test
    void validatorChecksValuesKeysAndReferences() {
        DataModel m = model();
        Table gender = m.tables().get(0);
        Table person = m.tables().get(1);
        Table badGender = gender.withRows(List.of(
                new Row("r1", Map.of("g1", "1", "g2", "F", "g3", "Female")),
                new Row("r2", Map.of("g1", "1", "g2", "XX", "g3", "Dup")),
                new Row("r3", Map.of("g1", "abc", "g3", "Bad"))));
        Table personRows = person.withRows(List.of(new Row("x", Map.of("p1", "1", "p2", "9"))));
        DataModel bad = m.withTables(List.of(badGender, personRows));
        List<String> messages = new ModelValidator().validate(bad).stream()
                .filter(i -> i.level() == ModelValidator.Level.ERROR).map(ModelValidator.Issue::message).toList();
        assertTrue(messages.stream().anyMatch(s -> s.contains("\"XX\" is longer than 1")), messages.toString());
        assertTrue(messages.stream().anyMatch(s -> s.contains("\"abc\" is not a valid smallint")), messages.toString());
        assertTrue(messages.stream().anyMatch(s -> s.contains("duplicate value 1 in id")), messages.toString());
        assertTrue(messages.stream().anyMatch(s -> s.contains("person row 1: 9 has no matching row in gender")), messages.toString());
        assertTrue(new ModelValidator().validate(m).stream().noneMatch(i -> i.level() == ModelValidator.Level.ERROR));

        Table empty = gender.withRows(List.of());
        assertTrue(new ModelValidator().validate(m.withTables(List.of(empty, person))).stream()
                .anyMatch(i -> i.message().equals("Lookup table gender has no data rows")));
    }

    @Test
    void applierDropsValuesOfDeletedColumnsAndCapsRows() {
        DataModel m = model();
        Table gender = m.tables().get(0);
        Table withoutName = new Table(gender.id(), gender.schema(), gender.name(), null, gender.columns().subList(0, 2),
                List.of(), List.of(), null, null, gender.kind(), gender.rows());
        DataModel updated = OperationApplier.apply(m, new ModelOperation.UpsertTable(withoutName));
        assertEquals(Map.of("g1", "1", "g2", "F"), updated.tables().get(0).rows().get(0).values());

        List<Row> tooMany = new ArrayList<>();
        for (int i = 0; i <= Table.MAX_ROWS; i++) {
            tooMany.add(new Row("r" + i, Map.of("g1", String.valueOf(i))));
        }
        assertThrows(InvalidOperationException.class,
                () -> OperationApplier.apply(m, new ModelOperation.UpsertTable(gender.withRows(tooMany))));
    }

    @Test
    void jsonWithoutKindOrRowsStillReads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Table t = mapper.readValue("{\"id\":\"t\",\"name\":\"a\",\"columns\":[]}", Table.class);
        assertEquals(TableKind.ENTITY, t.kind());
        assertTrue(t.rows().isEmpty());
        Table back = mapper.readValue(mapper.writeValueAsString(model().tables().get(0)), Table.class);
        assertEquals(model().tables().get(0), back);
    }
}
