package com.datagocar.model;

/**
 * What a table is for. {@code LOOKUP} tables (reference data such as genders, days of the week or company roles)
 * carry their rows in the model, and the exports insert them.
 */
public enum TableKind {
    ENTITY,
    LOOKUP
}
