package com.datagocar.model;

public enum FkAction {
    NO_ACTION("NO ACTION"), RESTRICT("RESTRICT"), CASCADE("CASCADE"), SET_NULL("SET NULL"), SET_DEFAULT("SET DEFAULT");

    private final String sql;

    FkAction(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
