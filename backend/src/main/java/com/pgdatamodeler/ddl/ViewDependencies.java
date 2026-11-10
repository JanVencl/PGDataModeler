package com.pgdatamodeler.ddl;

import com.pgdatamodeler.ddl.SqlLexer.Kind;
import com.pgdatamodeler.ddl.SqlLexer.Token;
import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.model.Table;
import com.pgdatamodeler.model.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the tables and views a view's query reads, by looking at the names in its FROM / JOIN clauses.
 * A heuristic (no full SQL parser): good enough to order views and draw dependencies. Keep in sync with
 * {@code frontend/src/model/views.ts}.
 */
public final class ViewDependencies {

    private static final Set<String> FROM_END = Set.of(
            "where", "group", "having", "order", "limit", "offset", "union", "intersect", "except", "window",
            "fetch", "for", "select", "on", "using");

    private ViewDependencies() {
    }

    /** Ids of the tables and views (other than itself) that {@code view} reads, in order of first mention. */
    public static List<String> of(DataModel model, View view) {
        Set<String> ids = new LinkedHashSet<>();
        for (List<String> name : relationNames(view.definition())) {
            String schema = name.size() > 1 ? name.get(name.size() - 2) : null;
            String rel = name.get(name.size() - 1);
            for (Table t : model.tables()) {
                if (matches(t.schema(), t.name(), schema, rel)) {
                    ids.add(t.id());
                }
            }
            for (View v : model.views()) {
                if (!v.id().equals(view.id()) && matches(v.schema(), v.name(), schema, rel)) {
                    ids.add(v.id());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** An unqualified name matches the object in any schema (the search_path is not known at design time). */
    private static boolean matches(String objSchema, String objName, String schema, String name) {
        return objName.equals(name) && (schema == null || objSchema.equals(schema));
    }

    /** Dotted names that follow FROM, JOIN, ONLY or a comma inside a FROM list. */
    static List<List<String>> relationNames(String sql) {
        List<Token> tokens = new ArrayList<>();
        SqlLexer.split(sql == null ? "" : sql).forEach(st -> tokens.addAll(st.tokens()));
        List<List<String>> names = new ArrayList<>();
        Deque<Boolean> inFrom = new ArrayDeque<>();
        inFrom.push(false);
        boolean expectName = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                inFrom.push(false);
                expectName = false;
                continue;
            }
            if (t.isSymbol(")")) {
                if (inFrom.size() > 1) {
                    inFrom.pop();
                }
                expectName = false;
                continue;
            }
            if (t.kind() == Kind.WORD) {
                String w = t.lower();
                if (w.equals("from") || w.equals("join")) {
                    inFrom.pop();
                    inFrom.push(true);
                    expectName = true;
                    continue;
                }
                if (FROM_END.contains(w)) {
                    if (!w.equals("on") && !w.equals("using")) {
                        inFrom.pop();
                        inFrom.push(false);
                    }
                    expectName = false;
                    continue;
                }
                if (expectName && (w.equals("only") || w.equals("lateral"))) {
                    continue;
                }
            }
            if (t.isSymbol(",") && Boolean.TRUE.equals(inFrom.peek())) {
                expectName = true;
                continue;
            }
            if (expectName && (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED_IDENT)) {
                List<String> name = new ArrayList<>();
                name.add(ident(t));
                while (i + 2 < tokens.size() && tokens.get(i + 1).isSymbol(".")
                        && (tokens.get(i + 2).kind() == Kind.WORD || tokens.get(i + 2).kind() == Kind.QUOTED_IDENT)) {
                    name.add(ident(tokens.get(i + 2)));
                    i += 2;
                }
                names.add(name);
            }
            expectName = false;
        }
        return names;
    }

    private static String ident(Token t) {
        return t.kind() == Kind.WORD ? t.lower() : t.text();
    }

    /** Views ordered so that every view comes after the views it reads; ties by schema and name. */
    public static List<View> creationOrder(DataModel model) {
        List<View> sorted = model.views().stream()
                .sorted(Comparator.comparing(View::schema).thenComparing(View::name))
                .toList();
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (View v : sorted) {
            deps.put(v.id(), of(model, v).stream().filter(id -> model.view(id).isPresent()).toList());
        }
        List<View> result = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        boolean progress = true;
        while (result.size() < sorted.size() && progress) {
            progress = false;
            for (View v : sorted) {
                if (!done.contains(v.id()) && done.containsAll(deps.get(v.id()))) {
                    result.add(v);
                    done.add(v.id());
                    progress = true;
                }
            }
        }
        // cycles (invalid anyway): keep the remaining views in name order
        sorted.stream().filter(v -> !done.contains(v.id())).forEach(result::add);
        return result;
    }
}
