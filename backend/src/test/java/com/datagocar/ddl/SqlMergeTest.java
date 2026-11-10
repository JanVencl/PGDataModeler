package com.datagocar.ddl;

import com.datagocar.model.Column;
import com.datagocar.model.DataModel;
import com.datagocar.model.Position;
import com.datagocar.model.Relationship;
import com.datagocar.model.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMergeTest {

    private static Table table(DataModel m, String name) {
        return m.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Column column(Table t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** customer(id, email) at (100, 50), painted red; orders(id, customer_id) → customer. */
    private static DataModel base() {
        DataModel m = PostgresDdlImporter.parse("""
                CREATE TABLE customer (id bigint PRIMARY KEY, email text);
                CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint REFERENCES customer);
                CREATE TYPE mood AS ENUM ('sad', 'ok');
                """, "Shop").model();
        Table c = table(m, "customer");
        Table moved = new Table(c.id(), c.schema(), c.name(), "kept comment", c.columns(), c.indexes(), c.checks(),
                new Position(100, 50), "#ff0000", c.kind(), c.rows());
        return m.withTables(m.tables().stream().map(t -> t.id().equals(c.id()) ? moved : t).toList());
    }

    @Test
    void newTablesReferToExistingOnesAndExistingKeepTheirPlace() {
        DataModel before = base();
        var result = PostgresDdlImporter.merge("""
                CREATE TABLE invoice (id bigint PRIMARY KEY, order_id bigint REFERENCES orders(id));
                ALTER TABLE customer ADD COLUMN phone text;
                COMMENT ON COLUMN customer.email IS 'Login';
                """, before, true);
        DataModel m = result.model();

        assertEquals(3, m.tables().size());
        Table customer = table(m, "customer");
        assertEquals(table(before, "customer").id(), customer.id());
        assertEquals(new Position(100, 50), customer.position());
        assertEquals("#ff0000", customer.color());
        assertEquals(column(table(before, "customer"), "email").id(), column(customer, "email").id());
        assertEquals("Login", column(customer, "email").comment());
        assertEquals("phone", customer.columns().get(2).name());

        Table invoice = table(m, "invoice");
        assertTrue(invoice.position().x() > 100, "new tables are placed right of the existing ones");
        assertEquals(2, m.relationships().size());
        Relationship fk = m.relationships().stream().filter(r -> r.fromTableId().equals(invoice.id())).findFirst().orElseThrow();
        assertEquals(table(m, "orders").id(), fk.toTableId());
    }

    @Test
    void replaceRedefinesButKeepsIdsAndComments() {
        DataModel before = base();
        DataModel m = PostgresDdlImporter.merge("""
                CREATE TABLE customer (id bigint PRIMARY KEY, name text NOT NULL);
                CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint REFERENCES customer);
                CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');
                """, before, true).model();

        Table customer = table(m, "customer");
        assertEquals(table(before, "customer").id(), customer.id());
        assertEquals("kept comment", customer.comment());
        assertEquals(column(table(before, "customer"), "id").id(), column(customer, "id").id());
        assertEquals("name", customer.columns().get(1).name());
        assertEquals(1, m.relationships().size(), "the identical foreign key is not duplicated");
        assertEquals(before.relationships().get(0).id(), m.relationships().get(0).id());
        assertEquals(before.enums().get(0).id(), m.enums().get(0).id());
        assertEquals(3, m.enums().get(0).values().size());
    }

    @Test
    void keepLeavesExistingDefinitions() {
        DataModel before = base();
        var result = PostgresDdlImporter.merge("""
                CREATE TABLE customer (id bigint PRIMARY KEY, name text NOT NULL);
                CREATE TABLE tag (id bigint PRIMARY KEY);
                """, before, false);
        DataModel m = result.model();
        assertEquals(table(before, "customer"), table(m, "customer"));
        assertEquals(3, m.tables().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("customer already exists")));
    }

    @Test
    void redefinitionDroppingAReferencedColumnDropsTheForeignKey() {
        DataModel before = base();
        var result = PostgresDdlImporter.merge("CREATE TABLE orders (id bigint PRIMARY KEY);", before, true);
        assertEquals(0, result.model().relationships().size());
        assertNotEquals(0, result.warnings().size());
    }
}
