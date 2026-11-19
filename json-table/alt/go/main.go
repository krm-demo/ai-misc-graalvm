// The same table as the Java json-table, with the Go standard library only (no colours).
// Usage: json-table-go FILE      or      cat FILE | json-table-go -
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"unicode/utf8"
)

// encoding/json decodes objects into maps, which lose the order of the properties,
// so the input is read token by token into these ordered types instead.
type entry struct {
	key   string
	value any
}
type object []entry
type array []any

var known = []string{"id", "name", "createdAt"}

func parse(dec *json.Decoder) (any, error) {
	token, err := dec.Token()
	if err != nil {
		return nil, err
	}
	delim, isDelim := token.(json.Delim)
	if !isDelim {
		return token, nil // string, json.Number, bool or nil
	}
	if delim == '[' {
		items := array{}
		for dec.More() {
			item, err := parse(dec)
			if err != nil {
				return nil, err
			}
			items = append(items, item)
		}
		_, err = dec.Token() // the closing ']'
		return items, err
	}
	obj := object{}
	for dec.More() {
		key, err := dec.Token()
		if err != nil {
			return nil, err
		}
		value, err := parse(dec)
		if err != nil {
			return nil, err
		}
		obj = append(obj, entry{key.(string), value})
	}
	_, err = dec.Token() // the closing '}'
	return obj, err
}

func (o object) get(key string) any {
	for _, e := range o {
		if e.key == key {
			return e.value
		}
	}
	return nil
}

func quote(s string) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(s)
	return strings.TrimSuffix(buf.String(), "\n")
}

// Pretty JSON in the layout of Jackson's default pretty printer.
func pretty(value any, indent string) string {
	switch v := value.(type) {
	case object:
		if len(v) == 0 {
			return "{ }"
		}
		inner := indent + "  "
		parts := make([]string, len(v))
		for i, e := range v {
			parts[i] = inner + quote(e.key) + " : " + pretty(e.value, inner)
		}
		return "{\n" + strings.Join(parts, ",\n") + "\n" + indent + "}"
	case array:
		if len(v) == 0 {
			return "[ ]"
		}
		parts := make([]string, len(v))
		for i, item := range v {
			parts[i] = pretty(item, indent)
		}
		return "[ " + strings.Join(parts, ", ") + " ]"
	case string:
		return quote(v)
	case nil:
		return "null"
	default:
		return fmt.Sprint(v) // json.Number and bool
	}
}

func cell(value any) string {
	switch v := value.(type) {
	case nil:
		return ""
	case string:
		return v
	default:
		return pretty(v, "")
	}
}

func pad(s string, width int) string {
	return s + strings.Repeat(" ", max(0, width-utf8.RuneCountInString(s)))
}

func render(items []object, out *bufio.Writer) {
	seen := map[string]bool{}
	for _, key := range known {
		seen[key] = true
	}
	keys := append([]string{}, known...)
	headers := append([]string{}, known...)
	for _, item := range items {
		for _, e := range item {
			if !seen[e.key] {
				seen[e.key] = true
				keys = append(keys, e.key)
				headers = append(headers, `"`+e.key+`"`)
			}
		}
	}
	widths := make([]int, len(headers))
	for c, header := range headers {
		widths[c] = utf8.RuneCountInString(header)
	}
	rows := make([][][]string, len(items))
	for r, item := range items {
		rows[r] = make([][]string, len(keys))
		for c, key := range keys {
			lines := strings.Split(cell(item.get(key)), "\n")
			rows[r][c] = lines
			for _, line := range lines {
				widths[c] = max(widths[c], utf8.RuneCountInString(line))
			}
		}
	}
	writeLine := func(cellAt func(c int) string) {
		for c := range headers {
			if c > 0 {
				out.WriteString(" │ ")
			}
			out.WriteString(pad(cellAt(c), widths[c]))
		}
		out.WriteByte('\n')
	}
	rules := make([]string, len(widths))
	for c, width := range widths {
		rules[c] = strings.Repeat("─", width)
	}
	rule := strings.Join(rules, "─┼─") + "\n"

	writeLine(func(c int) string { return headers[c] })
	out.WriteString(rule)
	for _, row := range rows {
		height := 1
		for _, lines := range row {
			height = max(height, len(lines))
		}
		for i := 0; i < height; i++ {
			writeLine(func(c int) string {
				if i < len(row[c]) {
					return row[c][i]
				}
				return ""
			})
		}
		out.WriteString(rule)
	}
}

func run(args []string) error {
	var in io.Reader = os.Stdin
	if args[0] != "-" {
		file, err := os.Open(args[0])
		if err != nil {
			return err
		}
		defer file.Close()
		in = file
	}
	dec := json.NewDecoder(bufio.NewReaderSize(in, 1<<16))
	dec.UseNumber()
	value, err := parse(dec)
	if err != nil {
		return err
	}
	notTable := errors.New("the input is not a JSON array of objects")
	list, ok := value.(array)
	if !ok {
		return notTable
	}
	items := make([]object, len(list))
	for i, item := range list {
		if items[i], ok = item.(object); !ok {
			return notTable
		}
	}
	out := bufio.NewWriterSize(os.Stdout, 1<<16)
	render(items, out)
	return out.Flush()
}

func main() {
	args := os.Args[1:]
	if len(args) == 0 || args[0] == "-h" || args[0] == "--help" {
		fmt.Println("Usage: json-table-go FILE      or      cat FILE | json-table-go -")
		fmt.Println("Prints a JSON array of objects as a table.")
		return
	}
	if err := run(args); err != nil {
		fmt.Fprintln(os.Stderr, "ERROR:", err)
		os.Exit(1)
	}
}
