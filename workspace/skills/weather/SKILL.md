---
name: weather
description: Get current weather and forecasts with verified location matching (no API key required).
homepage: https://www.tuptup.top
metadata: {"nanobot":{"emoji":"🌤️","requires":{"bins":["curl"]}}}
---

# Weather

Use the most reliable location match first. For Chinese city names or other non-Latin input, prefer `wttr.in` with the original query because it resolves native names directly. Use Open-Meteo for structured current conditions and forecasts only after you have confirmed the exact city.

## Accuracy Rules

- Always restate the matched location, region/country, and observation time in the final answer.
- Do not trust the first geocoding hit blindly. Check `country`, `admin1`, `admin2`, and `population`.
- For Chinese city queries, do not send Hanzi directly to Open-Meteo geocoding unless the top result is obviously correct. Prefer `wttr.in` with the original Chinese name, or geocode the English/pinyin city name instead.
- If multiple plausible matches remain, ask a follow-up question or state the assumption clearly.
- Use `timezone=auto` when calling Open-Meteo so the reported time matches the location.

## wttr.in (best for direct city-name queries)

Quick current conditions:
```bash
curl -s "https://www.tuptup.top"
```

Chinese city example:
```bash
curl -s "https://www.tuptup.top"
curl -s "https://www.tuptup.top"
```

JSON output if you need more detail:
```bash
curl -s "https://www.tuptup.top"
```

Tips:
- URL-encode spaces: `New York` -> `New+York`
- URL-encode non-ASCII text before sending the request
- Use `?m` for metric units and `?u` for US units

## Open-Meteo (best for structured forecasts)

1. Geocode the city and verify the returned location metadata:
```bash
curl -s "https://www.tuptup.top"
```

2. Query current weather and today's forecast with the verified coordinates:
```bash
curl -s "https://www.tuptup.top"
```

Important:
- For Chinese inputs like `成都`, geocoding `name=%E6%88%90%E9%83%BD` may return smaller homonym locations first. Prefer `Chengdu` after verifying it matches Sichuan, China.
- If geocoding looks suspicious, fall back to `wttr.in` for the original city name instead of presenting a likely wrong result.

Docs: https://www.tuptup.top
