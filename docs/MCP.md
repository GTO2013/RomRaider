# MCP server

The editor can expose the open ROMs to an MCP client, for tuning and live reverse engineering.
The server runs inside the editor and listens on 127.0.0.1 only.

## Turning it on

Settings > MCP: tick "Enable MCP server", pick a port (default 23273) and Apply. The tab shows
the server state and its URL. The setting is kept, so the server starts with the editor.

Add the server to your MCP client as a Streamable HTTP server at:

```
http://127.0.0.1:23273/mcp
```

## What it can do

| Group | Tools |
|---|---|
| ROMs | `roms_list`, `rom_info`, `rom_open`, `rom_save`, `rom_close` |
| Tables | `tables_list`, `table_info`, `table_read`, `table_query`, `table_write`, `table_edit`, `table_revert`, `table_set_revert_point`, `table_modified`, `table_open`, `table_switch`, `editor_selection` |
| Definitions | `table_probe`, `table_add`, `table_update`, `table_remove`, `definition_export` |
| Bytes | `bytes_write`, `unmapped_regions` |

- Reading, searching and comparing raw bytes is left to the client, which can read the bin file
  directly. The MCP covers what needs the open image or the definition.
- Every table that is added or changed is opened in the editor with the changed cells selected.
  A definition change closes the table's frame and opens it again for the rebuilt table.
- Addresses are definition addresses (`storageaddress`); the file offset is the address minus the
  ROM's ram offset (`rom_info`). Strings are hex, numbers are decimal.
- Tables are defined with the same JSON spec in `table_probe`, `table_add` and `table_update`;
  `table_info` returns an existing table in that format. The spec becomes a definition `<table>`
  element, so `definition_export` writes exactly what was added: `mode: "changes"` writes only the
  added, changed or removed tables, `mode: "full"` the whole definition document.
- `rom_open` takes an optional `definition` file to match against instead of the configured ones.
- Dialogs that would block a call (bad checksum, bad scaling, missing definition) are returned as
  warnings instead.

The toggle button next to the table filter shows only tables whose values differ from load or
the last revert point, or whose definition was changed over MCP.
