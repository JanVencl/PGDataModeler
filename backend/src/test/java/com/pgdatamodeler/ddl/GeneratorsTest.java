package com.pgdatamodeler.ddl;

import com.pgdatamodeler.Fixtures;
import com.pgdatamodeler.model.DataModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorsTest {

    private final DataModel shop = Fixtures.shop();

    @Test
    void sqlContainsAllObjectsInDependencyOrder() {
        String sql = new PostgresDdlGenerator().generate(shop);
        assertTrue(sql.contains("CREATE SCHEMA IF NOT EXISTS sales;"));
        assertTrue(sql.contains("CREATE TYPE public.order_status AS ENUM ('new', 'paid', 'closed');"));
        assertTrue(sql.contains("id bigint GENERATED ALWAYS AS IDENTITY NOT NULL"));
        assertTrue(sql.contains("created_at timestamptz DEFAULT now() NOT NULL"));
        assertTrue(sql.contains("CONSTRAINT customer_email_key UNIQUE (email)"));
        // "order" is a reserved word and must be quoted
        assertTrue(sql.contains("CREATE TABLE sales.\"order\" ("));
        assertTrue(sql.contains("CONSTRAINT order_check CHECK (total >= 0)"));
        assertTrue(sql.contains("COMMENT ON COLUMN public.customer.email IS 'Login e-mail';"));
        assertTrue(sql.contains("ADD CONSTRAINT order_customer_id_fkey FOREIGN KEY (customer_id)"));
        assertTrue(sql.contains("REFERENCES public.customer (id) ON DELETE CASCADE;"));
        assertTrue(sql.contains("CREATE INDEX order_customer_id_idx ON sales.\"order\" (customer_id);"));
        assertTrue(sql.contains("CREATE INDEX order_open_idx ON sales.\"order\" (status) WHERE status <> 'closed';"));
        assertTrue(sql.indexOf("CREATE TYPE") < sql.indexOf("CREATE TABLE"));
        assertTrue(sql.lastIndexOf("CREATE TABLE") < sql.indexOf("FOREIGN KEY"));
    }

    @Test
    void liquibaseXmlUsesNativeChangesAndSqlFallbacks() {
        String xml = new LiquibaseXmlGenerator().generate(shop, "pgdatamodeler");
        assertTrue(xml.contains("<createTable schemaName=\"public\" tableName=\"customer\" remarks=\"People who buy things\">"));
        assertTrue(xml.contains("autoIncrement=\"true\" generationType=\"ALWAYS\""));
        assertTrue(xml.contains("<constraints primaryKey=\"true\" primaryKeyName=\"customer_pkey\" nullable=\"false\"/>"));
        assertTrue(xml.contains("defaultValueComputed=\"&apos;new&apos;\""));
        assertTrue(xml.contains("<addForeignKeyConstraint constraintName=\"order_customer_id_fkey\""));
        assertTrue(xml.contains("onDelete=\"CASCADE\""));
        assertTrue(xml.contains("<createIndex schemaName=\"sales\" tableName=\"order\" indexName=\"order_customer_id_idx\">"));
        assertTrue(xml.contains("WHERE status <> 'closed'"));
        assertTrue(xml.contains("<rollback><![CDATA[DROP TYPE public.order_status;]]></rollback>"));
        assertTrue(xml.trim().endsWith("</databaseChangeLog>"));
    }

    @Test
    void liquibaseFormattedSqlHasOneChangesetPerUnit() {
        String sql = new LiquibaseSqlGenerator().generate(shop, "jan");
        assertTrue(sql.startsWith("--liquibase formatted sql"));
        assertTrue(sql.contains("--changeset jan:table-sales.order"));
        long changesets = sql.lines().filter(l -> l.startsWith("--changeset")).count();
        assertEquals(new PostgresDdlGenerator().units(shop).size(), changesets);
    }

    @Test
    void validatorFlagsReservedNamesButNoErrorsForShop() {
        var issues = new ModelValidator().validate(shop);
        assertFalse(issues.stream().anyMatch(i -> i.level() == ModelValidator.Level.ERROR), issues.toString());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("reserved word")));
    }
}
