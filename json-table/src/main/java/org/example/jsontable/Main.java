package org.example.jsontable;

import static java.util.stream.Collectors.joining;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a JSON array of objects from a file, or from standard input when the argument is "-",
 * and prints it as a table.
 * Without arguments it prints the help.
 */
@Command(
        name = "json-table",
        mixinStandardHelpOptions = true,
        version = "json-table 1.0",
        sortOptions = false,
        description = {
            "",
            "Prints a JSON array of objects as a table.",
            "",
            "@|bold,underline Expected input|@",
            "  A JSON array whose elements are objects, for example:",
            "",
            "    [ { \"id\": \"T-1\", \"name\": \"Fix the login page\", \"assignee\": \"anna\" } ]",
            "",
            "  @|bold id|@, @|bold name|@        text, each in its own column",
            "  @|bold createdAt|@       ISO-8601 instant, in its own column",
            "  @|yellow anything else|@   one extra column per property; nested objects",
            "                  and arrays are shown as pretty JSON",
            ""
        },
        parameterListHeading = "@|bold,underline Parameters|@%n",
        optionListHeading = "%n@|bold,underline Options|@%n",
        footer = {
            "",
            "@|bold,underline Examples|@",
            "  json-table sample.json",
            "  json-table --date-only sample.json",
            "  cat sample.json | json-table -",
            "",
            "@|bold,underline Exit codes|@",
            "  0  success",
            "  1  the input could not be read or is not valid",
            "  2  wrong command-line arguments"
        })
public final class Main implements Callable<Integer> {

    private static final int EXIT_OK = 0;
    private static final int EXIT_INPUT_ERROR = 1;
    private static final int EXIT_USAGE_ERROR = 2;

    /** Colours only when attached to a terminal and NO_COLOR is not set. Assigned once in main(). */
    private static boolean color;

    private record Column(String header, boolean known, Function<Item, String> cell) {}

    /** What went wrong, as shown to the user: a coloured label and a one-line message. */
    private record Problem(String label, String message) {}

    @Parameters(index = "0", paramLabel = "FILE",
            description = "JSON file to read; use @|bold -|@ for standard input.")
    private String file;

    @Option(names = "--date-only",
            description = "Show @|bold createdAt|@ as a date (UTC), without the time.")
    private boolean dateOnly;

    @Option(names = "--color", paramLabel = "WHEN",
            description = "@|bold auto|@ (default: colours only on a terminal and without NO_COLOR), "
                    + "@|bold always|@ or @|bold never|@.")
    private String colorMode = "auto";

    @Option(names = "--timing",
            description = "Print the start time, the end time and the duration to standard error.")
    private boolean timing;

    @Option(names = "--debug",
            description = "On error, print the full stack trace instead of a one-line message.")
    private boolean debug;

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        color = wantColor(args);
        CommandLine cmd = new CommandLine(new Main())
                .setColorScheme(Help.defaultColorScheme(color ? Help.Ansi.ON : Help.Ansi.OFF))
                .setExecutionExceptionHandler(Main::onExecutionError)
                .setParameterExceptionHandler(Main::onUsageError);
        if (args.length == 0) {
            cmd.usage(System.out);
            System.exit(EXIT_OK);
        }
        System.exit(cmd.execute(args));
    }

    /**
     * The colour scheme is needed before picocli parses anything (the help and the error messages use it),
     * so --color is looked up by hand here; call() validates its value.
     */
    private static boolean wantColor(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String when = args[i].startsWith("--color=") ? args[i].substring("--color=".length())
                    : args[i].equals("--color") && i + 1 < args.length ? args[i + 1]
                    : "";
            if (when.equals("always")) {
                return true;
            }
            if (when.equals("never")) {
                return false;
            }
        }
        return isTerminal() && System.getenv("NO_COLOR") == null;
    }

    @Override
    public Integer call() throws IOException {
        if (!List.of("auto", "always", "never").contains(colorMode)) {
            throw new ParameterException(spec.commandLine(),
                    "Invalid value for option '--color': '" + colorMode + "' (expected auto, always or never)");
        }
        Instant start = Instant.now();
        if (timing) {
            System.err.println("start: " + start);
        }

        JsonMapper mapper = JsonMapper.builder().build();
        // readValues() steps into the top-level array and binds one element per next().
        // The table needs every row before it can print, so the stream is collected here.
        List<Item> items;
        try (InputStream in = "-".equals(file) ? System.in : Files.newInputStream(Path.of(file));
             MappingIterator<Item> elements = mapper.readerFor(Item.class).readValues(in)) {
            items = StreamSupport
                    .stream(Spliterators.spliteratorUnknownSize(elements, Spliterator.ORDERED), false)
                    .toList();
        }
        print(items, System.out, dateOnly);

        if (timing) {
            Instant end = Instant.now();
            System.err.println("end:   " + end + ", took " + Duration.between(start, end).toMillis() + " ms");
        }
        return EXIT_OK;
    }

    // ------------------------------------------------------------------ error reporting

    /** Anything thrown while reading or printing ends up here; nothing escapes as a raw stack trace. */
    private static int onExecutionError(Exception ex, CommandLine cmd, ParseResult parseResult) {
        Main app = cmd.getCommand();
        report(cmd, describe(ex), ex, app.debug);
        return EXIT_INPUT_ERROR;
    }

    /** Wrong options or a missing FILE. */
    private static int onUsageError(ParameterException ex, String[] args) {
        CommandLine cmd = ex.getCommandLine();
        report(cmd, new Problem("USAGE ERROR:", ex.getMessage()), ex, Arrays.asList(args).contains("--debug"));
        cmd.getErr().println("Run '" + cmd.getCommandName() + " --help' to see what is expected.");
        return EXIT_USAGE_ERROR;
    }

    private static void report(CommandLine cmd, Problem problem, Exception ex, boolean debug) {
        PrintWriter err = cmd.getErr();
        Help.ColorScheme scheme = cmd.getColorScheme();
        // Only the label goes through the markup parser; the message is printed as it is.
        err.println(scheme.ansi().string("@|bold,red " + problem.label() + "|@ ") + problem.message());
        if (debug) {
            err.println(scheme.richStackTraceString(ex));
        }
        err.flush();
    }

    private static Problem describe(Throwable ex) {
        return switch (ex) {
            case NoSuchFileException e -> new Problem("FILE NOT FOUND:", e.getFile());
            case AccessDeniedException e -> new Problem("ACCESS DENIED:", e.getFile());
            case DatabindException e -> new Problem("INVALID DATA:", jacksonMessage(e));
            case JacksonException e -> new Problem("INVALID JSON:", jacksonMessage(e));
            case IOException e -> new Problem("I/O ERROR:", messageOrType(e));
            default -> new Problem("ERROR:", messageOrType(ex));
        };
    }

    /** The parser's own message without the multi-line source dump, plus where it happened. */
    private static String jacksonMessage(JacksonException e) {
        var location = e.getLocation();
        String where = location == null || location.getLineNr() < 0
                ? ""
                : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
        return e.getOriginalMessage() + where;
    }

    private static String messageOrType(Throwable ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getName() : ex.getMessage();
    }

    /**
     * System.console() alone is not enough: since JDK 22 it can be non-null when output is redirected.
     * Console.isTerminal() exists only from JDK 22, and this project compiles for 21, hence the reflection.
     */
    private static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;   // before JDK 22 a non-null console means a terminal
        }
    }

    // ------------------------------------------------------------------ table

    static void print(List<Item> items, PrintStream out, boolean dateOnly) {
        List<Column> columns = new ArrayList<>(List.of(
                new Column("id", true, item -> text(item.id())),
                new Column("name", true, item -> text(item.name())),
                new Column("createdAt", true, item -> dateOnly ? date(item.createdAt()) : text(item.createdAt()))));

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

    private static String date(Instant instant) {
        return instant == null ? "" : LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String json(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        // Objects and arrays as pretty JSON; scalars as plain text. (asString() is the Jackson 3 name of asText().)
        return node.isObject() || node.isArray() ? node.toPrettyString() : node.asString();
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String dim(String s) {
        return style("2", s);
    }

    /** ANSI SGR codes: 1 = bold, 2 = dim, 33 = yellow. */
    private static String style(String code, String s) {
        return color ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
    }
}
