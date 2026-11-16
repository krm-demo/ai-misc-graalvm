package org.example.jsontable;

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a JSON array of objects from a file (first argument) or from standard input
 * and prints it as a table. Timing lines go to standard error, so the table can be piped.
 */
public final class Main {

    /** No colours when the output is not a terminal or when NO_COLOR is set. */
    private static final boolean COLOR = System.console() != null && System.getenv("NO_COLOR") == null;

    private record Column(String header, boolean known, Function<Item, String> cell) {}

    public static void main(String[] args) throws IOException {
        Instant start = Instant.now();
        System.err.println("start: " + start);

        JsonMapper mapper = JsonMapper.builder().build();
        // readValues() steps into the top-level array and binds one element per next().
        // The table needs every row before it can print, so the stream is collected here.
        List<Item> items;
        try (InputStream in = args.length > 0 ? Files.newInputStream(Path.of(args[0])) : System.in;
             MappingIterator<Item> elements = mapper.readerFor(Item.class).readValues(in)) {
            items = StreamSupport
                    .stream(Spliterators.spliteratorUnknownSize(elements, Spliterator.ORDERED), false)
                    .toList();
        }
        print(items, System.out);

        Instant end = Instant.now();
        System.err.println("end:   " + end + ", took " + Duration.between(start, end).toMillis() + " ms");
    }

    static void print(List<Item> items, PrintStream out) {
        List<Column> columns = new ArrayList<>(List.of(
                new Column("id", true, item -> text(item.id())),
                new Column("name", true, item -> text(item.name())),
                new Column("createdAt", true, item -> text(item.createdAt()))));

        // One extra column per unknown property, in order of first appearance, name in double quotes.
        items.stream()
                .flatMap(item -> item.unknown().keySet().stream())
                .distinct()
                .forEach(key -> columns.add(
                        new Column('"' + key + '"', false, item -> json(item.unknown().get(key)))));

        // rows -> cells -> lines of one cell (pretty JSON takes several lines)
        List<List<String[]>> rows = items.stream()
                .map(item -> columns.stream().map(c -> c.cell().apply(item).split("\\R", -1)).toList())
                .toList();

        // Widths are measured on plain text, before any ANSI codes are added.
        int[] widths = IntStream.range(0, columns.size())
                .map(c -> Math.max(
                        columns.get(c).header().length(),
                        rows.stream().flatMap(row -> Arrays.stream(row.get(c))).mapToInt(String::length).max().orElse(0)))
                .toArray();

        String separator = dim(" │ ");
        String rule = dim(Arrays.stream(widths).mapToObj("─"::repeat).collect(joining("─┼─")));

        out.println(IntStream.range(0, columns.size())
                .mapToObj(c -> {
                    String padded = pad(columns.get(c).header(), widths[c]);
                    return columns.get(c).known() ? style("1", padded) : style("1;33", padded);
                })
                .collect(joining(separator)));
        out.println(rule);

        for (List<String[]> row : rows) {
            int height = row.stream().mapToInt(lines -> lines.length).max().orElse(1);
            for (int line = 0; line < height; line++) {
                int l = line;
                out.println(IntStream.range(0, columns.size())
                        .mapToObj(c -> {
                            String[] lines = row.get(c);
                            String padded = pad(l < lines.length ? lines[l] : "", widths[c]);
                            return columns.get(c).known() ? padded : style("33", padded);
                        })
                        .collect(joining(separator)));
            }
            out.println(rule);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String json(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        // Objects and arrays as pretty JSON; scalars as plain text. (asString() is the Jackson 3 name of asText().)
        return node.isContainerNode() ? node.toPrettyString() : node.asString();
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String dim(String s) {
        return style("2", s);
    }

    /** ANSI SGR codes: 1 = bold, 2 = dim, 33 = yellow. */
    private static String style(String code, String s) {
        return COLOR ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
    }
}
