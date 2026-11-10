package com.datagocar.ddl;

import com.datagocar.Fixtures;
import com.datagocar.model.Column;
import com.datagocar.model.DataModel;
import com.datagocar.model.FkAction;
import com.datagocar.model.Identity;
import com.datagocar.model.Index;
import com.datagocar.model.Relationship;
import com.datagocar.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresDdlImporterTest {

    private static Table table(DataModel m, String name) {
        return m.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Column column(Table t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void handWrittenDdl() {
        String sql = """
                -- shop schema
                CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');
                /* customers */
                CREATE TABLE customer (
                    id serial PRIMARY KEY,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    "Display Name" text,
                    mood mood DEFAULT 'ok',
                    created_at timestamp with time zone NOT NULL DEFAULT now()
                );
                CREATE TABLE IF NOT EXISTS orders (
                    id bigint GENERATED ALWAYS AS IDENTITY,
                    customer_id integer NOT NULL REFERENCES customer ON DELETE CASCADE,
                    total numeric(19, 4) CHECK (total >= 0),
                    note text,
                    CONSTRAINT orders_pk PRIMARY KEY (id),
                    CONSTRAINT orders_customer_note_uq UNIQUE (customer_id, note)
                );
                CREATE INDEX ON orders (customer_id);
                CREATE UNIQUE INDEX orders_lower_note ON orders (lower(note));
                COMMENT ON TABLE customer IS 'People who buy';
                COMMENT ON COLUMN customer.email IS 'Login';
                CREATE VIEW v AS SELECT 1;
                INSERT INTO customer (email) VALUES ('a;b');
                """;
        var result = PostgresDdlImporter.parse(sql, "Shop");
        DataModel m = result.model();
        assertEquals("Shop", m.name());
        assertEquals(List.of("sad", "ok", "happy"), m.enums().get(0).values());

        Table customer = table(m, "customer");
        assertEquals("People who buy", customer.comment());
        Column id = column(customer, "id");
        assertEquals("integer", id.type());
        assertEquals(Identity.BY_DEFAULT, id.identity());
        assertTrue(id.primaryKey());
        Column email = column(customer, "email");
        assertEquals("varchar(255)", email.type());
        assertFalse(email.nullable());
        assertTrue(email.unique());
        assertEquals("Login", email.comment());
        assertTrue(customer.columns().stream().anyMatch(c -> c.name().equals("Display Name")));
        assertEquals("'ok'", column(customer, "mood").defaultValue());
        Column created = column(customer, "created_at");
        assertEquals("timestamptz", created.type());
        assertEquals("now()", created.defaultValue());

        Table orders = table(m, "orders");
        assertEquals(Identity.ALWAYS, column(orders, "id").identity());
        assertTrue(column(orders, "id").primaryKey());
        assertEquals("numeric(19, 4)", column(orders, "total").type());
        assertEquals("total >= 0", orders.checks().get(0).expression());
        assertNull(orders.checks().get(0).name(), "default name is not stored");
        assertEquals(2, orders.indexes().size());
        Index uq = orders.indexes().get(0);
        assertTrue(uq.unique());
        assertEquals("orders_customer_note_uq", uq.name());

        Relationship fk = m.relationships().get(0);
        assertEquals(orders.id(), fk.fromTableId());
        assertEquals(customer.id(), fk.toTableId());
        assertEquals(id.id(), fk.columns().get(0).toColumnId());
        assertEquals(FkAction.CASCADE, fk.onDelete());

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("expression")), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("CREATE VIEW (1)")), result.warnings().toString());
        assertFalse(customer.position().equals(orders.position()));
    }

    @Test
    void pgDumpOutput() {
        String sql = """
                SET statement_timeout = 0;
                SELECT pg_catalog.set_config('search_path', '', false);
                \\restrict abc123
                CREATE SCHEMA sales;
                ALTER SCHEMA sales OWNER TO app;
                CREATE TYPE sales.status AS ENUM (
                    'new',
                    'it''s paid'
                );
                CREATE FUNCTION public.touch() RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$BEGIN NEW.updated := now(); RETURN NEW; END;$$;
                CREATE TABLE public.customer (
                    id integer NOT NULL,
                    name character varying(100) DEFAULT 'anon'::character varying NOT NULL
                );
                ALTER TABLE public.customer OWNER TO app;
                CREATE SEQUENCE public.customer_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
                ALTER SEQUENCE public.customer_id_seq OWNED BY public.customer.id;
                CREATE TABLE sales."order" (
                    id bigint NOT NULL,
                    customer_id integer,
                    status sales.status DEFAULT 'new'::sales.status NOT NULL,
                    placed timestamp(3) without time zone
                );
                ALTER TABLE sales."order" ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
                    SEQUENCE NAME sales.order_id_seq
                    START WITH 1
                    INCREMENT BY 1
                    CACHE 1
                );
                ALTER TABLE ONLY public.customer ALTER COLUMN id SET DEFAULT nextval('public.customer_id_seq'::regclass);
                COPY public.customer (id, name) FROM stdin;
                1\tJan; not a statement
                \\.
                ALTER TABLE ONLY public.customer
                    ADD CONSTRAINT customer_pkey PRIMARY KEY (id);
                ALTER TABLE ONLY sales."order"
                    ADD CONSTRAINT order_pkey PRIMARY KEY (id);
                CREATE INDEX order_open ON sales."order" USING btree (status) WHERE (status <> 'it''s paid'::sales.status);
                CREATE TRIGGER t BEFORE UPDATE ON public.customer FOR EACH ROW EXECUTE FUNCTION public.touch();
                ALTER TABLE ONLY sales."order"
                    ADD CONSTRAINT order_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id) ON UPDATE CASCADE;
                GRANT ALL ON TABLE public.customer TO reader;
                """;
        var result = PostgresDdlImporter.parse(sql, null);
        DataModel m = result.model();
        assertEquals("Imported SQL", m.name());
        assertEquals(List.of("new", "it's paid"), m.enums().get(0).values());
        assertEquals("sales", m.enums().get(0).schema());

        Table customer = table(m, "customer");
        Column id = column(customer, "id");
        assertTrue(id.primaryKey());
        assertEquals(Identity.BY_DEFAULT, id.identity());
        assertNull(id.defaultValue());
        assertEquals("varchar(100)", column(customer, "name").type());
        assertEquals("'anon'", column(customer, "name").defaultValue());

        Table order = table(m, "order");
        assertEquals("sales", order.schema());
        assertEquals(Identity.BY_DEFAULT, column(order, "id").identity());
        assertEquals("sales.status", column(order, "status").type());
        assertEquals("'new'", column(order, "status").defaultValue());
        assertEquals("timestamp(3)", column(order, "placed").type());
        assertEquals("status <> 'it''s paid'::sales.status", order.indexes().get(0).where());
        assertEquals("order_open", order.indexes().get(0).name());

        Relationship fk = m.relationships().get(0);
        assertNull(fk.name(), "default FK name is not stored");
        assertEquals(FkAction.CASCADE, fk.onUpdate());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("CREATE FUNCTION (1)") && w.contains("CREATE TRIGGER (1)")),
                result.warnings().toString());
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("could not read")), result.warnings().toString());
    }

    @Test
    void roundTripsOwnExport() {
        DataModel shop = Fixtures.shop();
        String exported = new PostgresDdlGenerator().generate(shop);
        var result = PostgresDdlImporter.parse(exported, "Shop");
        assertEquals(exported, new PostgresDdlGenerator().generate(result.model()), result.warnings().toString());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());

        String liquibase = new LiquibaseSqlGenerator().generate(shop, "jan");
        assertEquals(exported, new PostgresDdlGenerator().generate(PostgresDdlImporter.parse(liquibase, "Shop").model()));
    }

    @Test
    void rejectsScriptWithoutTables() {
        assertThrows(IllegalArgumentException.class, () -> PostgresDdlImporter.parse("SELECT 1; -- nothing", null));
    }
}
