// Mock Google Fonts responses for offline next/font builds.
//
// NEXT_FONT_GOOGLE_MOCKED_RESPONSES=/app/multica/mock-google-fonts.js
//
// next/font calls this module with the exact Google Fonts CSS2 URL as the
// property key. We return a minimal @font-face CSS that points `src:` at
// local woff2 files (absolute paths, which next/font resolves with
// readFileSync when the env var is set). Fonts are the same families the
// multica web app imports from next/font/google; files are bundled from
// @fontsource-variable/* packages into /app/multica/fonts/.
module.exports = new Proxy(
  {},
  {
    get(_target, prop) {
      if (prop === "__esModule" || prop === "then" || prop === "toJSON") return undefined;
      if (typeof prop !== "string" || !prop.startsWith("https://fonts.googleapis.com")) {
        return undefined;
      }
      const family = /family=([^:&]+)/.exec(prop);
      if (!family) return undefined;
      const name = family[1].replace(/\+/g, " ");
      const italic = /ital/.test(prop);
      const css = cssFor(name, italic);
      if (!css) {
        throw new Error("mock-google-fonts.js: no local font for " + name);
      }
      return css;
    },
  }
);

function cssFor(name, italic) {
  const faces = [];
  switch (name) {
    case "Inter":
      faces.push("@font-face{font-family:'Inter';font-style:normal;font-weight:100 900;font-display:swap;src:url(/app/multica/fonts/inter-latin-wght-normal.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      if (italic) {
        faces.push("@font-face{font-family:'Inter';font-style:italic;font-weight:100 900;font-display:swap;src:url(/app/multica/fonts/inter-latin-wght-italic.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      }
      break;
    case "Geist Mono":
      faces.push("@font-face{font-family:'Geist Mono';font-style:normal;font-weight:100 900;font-display:swap;src:url(/app/multica/fonts/geist-mono-latin-wght-normal.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      break;
    case "Source Serif 4":
      faces.push("@font-face{font-family:'Source Serif 4';font-style:normal;font-weight:100 900;font-display:swap;src:url(/app/multica/fonts/source-serif-4-latin-wght-normal.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      if (italic) {
        faces.push("@font-face{font-family:'Source Serif 4';font-style:italic;font-weight:100 900;font-display:swap;src:url(/app/multica/fonts/source-serif-4-latin-wght-italic.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      }
      break;
    case "Instrument Serif":
      faces.push("@font-face{font-family:'Instrument Serif';font-style:normal;font-weight:400;font-display:swap;src:url(/app/multica/fonts/instrument-serif-latin-400-normal.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}");
      break;
    default:
      return null;
  }
  return "/* latin */\n" + faces.join("\n");
}