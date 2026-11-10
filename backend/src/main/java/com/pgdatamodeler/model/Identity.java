package com.pgdatamodeler.model;

/** PostgreSQL identity column mode ({@code GENERATED ... AS IDENTITY}). */
public enum Identity {
    NONE, ALWAYS, BY_DEFAULT
}
