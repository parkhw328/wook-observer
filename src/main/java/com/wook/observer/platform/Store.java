package com.wook.observer.platform;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Profile("!collector")
public class Store {
    public final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
            return normalized;
        }).toList();
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) { throw new ApiProblem(404, "항목을 찾을 수 없습니다."); }
        return rows.getFirst();
    }

    public static String string(Map<String, Object> row, String name) {
        return row.get(name) == null ? "" : row.get(name).toString();
    }
}
