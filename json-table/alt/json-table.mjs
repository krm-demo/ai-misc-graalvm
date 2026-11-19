#!/usr/bin/env node
// The same table as the Java json-table, with Node.js built-ins only (no colours).
// Usage: json-table.mjs FILE      or      cat FILE | json-table.mjs -
import { readFileSync } from 'node:fs';

const KNOWN = ['id', 'name', 'createdAt'];
const isContainer = (value) => value !== null && typeof value === 'object';

// Pretty JSON in the layout of Jackson's default pretty printer.
function pretty(value, indent = '') {
  if (Array.isArray(value)) {
    return value.length ? '[ ' + value.map((item) => pretty(item, indent)).join(', ') + ' ]' : '[ ]';
  }
  if (isContainer(value)) {
    const entries = Object.entries(value);
    if (!entries.length) return '{ }';
    const inner = indent + '  ';
    return '{\n'
      + entries.map(([key, item]) => `${inner}${JSON.stringify(key)} : ${pretty(item, inner)}`).join(',\n')
      + '\n' + indent + '}';
  }
  return JSON.stringify(value);
}

const text = (value) => (value == null ? '' : typeof value === 'string' ? value : JSON.stringify(value));
const cell = (value) => (isContainer(value) ? pretty(value) : text(value));

function render(items) {
  const extra = [...new Set(items.flatMap((item) => Object.keys(item)).filter((key) => !KNOWN.includes(key)))];
  const headers = [...KNOWN, ...extra.map((key) => `"${key}"`)];
  const rows = items.map((item) => [
    ...KNOWN.map((key) => text(item[key]).split('\n')),
    ...extra.map((key) => cell(item[key]).split('\n')),
  ]);
  const widths = headers.map((header, c) =>
    rows.reduce((width, row) => row[c].reduce((w, line) => Math.max(w, line.length), width), header.length));
  const join = (cells) => cells.map((value, c) => value.padEnd(widths[c])).join(' │ ');
  const rule = widths.map((width) => '─'.repeat(width)).join('─┼─');
  const lines = [join(headers), rule];
  for (const row of rows) {
    const height = Math.max(...row.map((column) => column.length));
    for (let i = 0; i < height; i++) lines.push(join(row.map((column) => column[i] ?? '')));
    lines.push(rule);
  }
  return lines.join('\n') + '\n';
}

const [file] = process.argv.slice(2);
if (!file || file === '-h' || file === '--help') {
  console.log('Usage: json-table.mjs FILE      or      cat FILE | json-table.mjs -\n'
    + 'Prints a JSON array of objects as a table.');
} else {
  try {
    const items = JSON.parse(readFileSync(file === '-' ? 0 : file, 'utf8'));
    if (!Array.isArray(items) || !items.every((item) => isContainer(item) && !Array.isArray(item))) {
      throw new Error('the input is not a JSON array of objects');
    }
    process.stdout.write(render(items));
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}
