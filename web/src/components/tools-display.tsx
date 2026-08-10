import { Badge } from "./ui/badge";

export function ToolsDisplay({ tools }: { tools: any[] }) {
  if (!tools || tools.length === 0) return null;

  return (
    <div className="space-y-2">
      <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">命令</h4>
      <div className="grid gap-2">
        {tools.map((tool: any) => (
          <div key={tool.name} className="flex items-start gap-3 p-2.5 rounded-lg border bg-muted/20">
            {tool.command && (
              <Badge variant="secondary" className="font-mono text-xs shrink-0">/{tool.command}</Badge>
            )}
            <div className="min-w-0">
              <p className="text-sm font-medium">{tool.description || tool.name}</p>
              {tool.parameters?.properties && (
                <div className="flex flex-wrap gap-1 mt-1">
                  {Object.entries(tool.parameters.properties).map(([key, prop]: [string, any]) => (
                    <span key={key} className="text-[10px] text-muted-foreground font-mono">
                      {key}
                      {prop.description && <span className="text-muted-foreground/60"> ({prop.description})</span>}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Parse tools from API data (may be JSON string or array). */
export function parseTools(raw: any): any[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}
