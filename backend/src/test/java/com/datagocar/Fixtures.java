package com.datagocar;

import com.datagocar.model.CheckConstraint;
import com.datagocar.model.Column;
import com.datagocar.model.ColumnPair;
import com.datagocar.model.DataModel;
import com.datagocar.model.EnumType;
import com.datagocar.model.FkAction;
import com.datagocar.model.Identity;
import com.datagocar.model.Index;
import com.datagocar.model.Position;
import com.datagocar.model.Relationship;
import com.datagocar.model.Table;

import java.util.List;

/** A small shop model used by several tests. */
public final class Fixtures {
    private Fixtures() {
    }

    public static Column col(String id, String name, String type) {
        return new Column(id, name, type, true, false, false, null, Identity.NONE, null);
    }

    public static DataModel shop() {
        Table customer = new Table("t1", "public", "customer", "People who buy things",
                List.of(
                        new Column("c1", "id", "bigint", false, true, false, null, Identity.ALWAYS, null),
                        new Column("c2", "email", "varchar(255)", false, false, true, null, Identity.NONE, "Login e-mail"),
                        new Column("c3", "created_at", "timestamptz", false, false, false, "now()", Identity.NONE, null)),
                List.of(), List.of(), new Position(0, 0), null);
        Table order = new Table("t2", "sales", "order", null,
                List.of(
                        new Column("o1", "id", "bigint", false, true, false, null, Identity.ALWAYS, null),
                        new Column("o2", "customer_id", "bigint", false, false, false, null, Identity.NONE, null),
                        new Column("o3", "status", "order_status", false, false, false, "'new'", Identity.NONE, null),
                        new Column("o4", "total", "numeric(19,4)", false, false, false, "0", Identity.NONE, null)),
                List.of(new Index("i1", null, List.of("o2"), false, "btree", null),
                        new Index("i2", "order_open_idx", List.of("o3"), false, "btree", "status <> 'closed'")),
                List.of(new CheckConstraint("k1", null, "total >= 0")),
                new Position(300, 0), null);
        Relationship fk = new Relationship("r1", null, "t2", "t1",
                List.of(new ColumnPair("o2", "c1")), FkAction.CASCADE, null);
        EnumType status = new EnumType("e1", "public", "order_status", List.of("new", "paid", "closed"));
        return new DataModel("Shop", List.of(customer, order), List.of(fk), List.of(status));
    }
}
