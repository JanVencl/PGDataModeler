package com.datagocar.ops;

import com.datagocar.Fixtures;
import com.datagocar.model.ColumnPair;
import com.datagocar.model.DataModel;
import com.datagocar.model.Relationship;
import com.datagocar.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationApplierTest {

    @Test
    void deleteTableRemovesItsRelationships() {
        DataModel m = OperationApplier.apply(Fixtures.shop(), new ModelOperation.DeleteTable("t1"));
        assertEquals(1, m.tables().size());
        assertTrue(m.relationships().isEmpty());
    }

    @Test
    void removingFkColumnDropsRelationshipAndIndex() {
        DataModel shop = Fixtures.shop();
        Table order = shop.table("t2").orElseThrow();
        Table withoutCustomerId = new Table(order.id(), order.schema(), order.name(), order.comment(),
                order.columns().stream().filter(c -> !c.id().equals("o2")).toList(),
                order.indexes(), order.checks(), order.position(), order.color());
        DataModel m = OperationApplier.apply(shop, new ModelOperation.UpsertTable(withoutCustomerId));
        assertTrue(m.relationships().isEmpty());
        assertEquals(1, m.table("t2").orElseThrow().indexes().size());
    }

    @Test
    void upsertIsIdempotentAndKeepsOrder() {
        DataModel shop = Fixtures.shop();
        var op = new ModelOperation.UpsertTable(shop.tables().get(0));
        DataModel once = OperationApplier.apply(shop, op);
        DataModel twice = OperationApplier.apply(once, op);
        assertEquals(once, twice);
        assertEquals("t1", twice.tables().get(0).id());
    }

    @Test
    void moveUpdatesPositionAndIgnoresUnknownTable() {
        DataModel m = OperationApplier.apply(Fixtures.shop(), new ModelOperation.MoveTable("t1", 10, 20));
        assertEquals(10, m.table("t1").orElseThrow().position().x());
        assertEquals(m, OperationApplier.apply(m, new ModelOperation.MoveTable("nope", 1, 1)));
    }

    @Test
    void relationshipToUnknownColumnIsRejected() {
        var bad = new Relationship("r2", null, "t2", "t1", List.of(new ColumnPair("o2", "missing")), null, null);
        assertThrows(InvalidOperationException.class,
                () -> OperationApplier.apply(Fixtures.shop(), new ModelOperation.UpsertRelationship(bad)));
    }

    @Test
    void blankTableNameIsRejected() {
        Table t = Fixtures.shop().tables().get(0);
        Table blank = new Table(t.id(), t.schema(), " ", null, t.columns(), null, null, null, null);
        assertThrows(InvalidOperationException.class,
                () -> OperationApplier.apply(Fixtures.shop(), new ModelOperation.UpsertTable(blank)));
    }
}
