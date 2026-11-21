Looking up data through MCP tools:
- Prefer the most direct read tool whose required arguments you already have.
  List directories, collections, or inventory only to resolve a missing identifier or a detail the user asked for.
- Reuse IDs and results already established in this task while they still apply.
  When a tool accepts a list of IDs, pass the largest batch its documentation allows for the requested scope.
- Repeat a lookup only to cover IDs still missing, fetch another page, refresh data that may have changed,
  or recover from a specific error. Calling the same tool name again is fine when the arguments differ.
- When a result is truncated and carries a fullResult reference, read that saved result instead of
  running the same query again. Stop reading once the requested scope is covered.
