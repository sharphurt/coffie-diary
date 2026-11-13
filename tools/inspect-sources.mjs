import fs from "node:fs";
for (const name of ["folk", "tasty"]) {
  const html = fs.readFileSync(`docs/${name}-live.html`, "utf8");
  const ld = [
    ...html.matchAll(
      /<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g,
    ),
  ].map((m) => {
    try {
      return JSON.parse(m[1]);
    } catch {
      return null;
    }
  });
  const scripts = [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(
    (m) => m[1],
  );
  console.log(
    JSON.stringify(
      {
        source: name,
        bytes: html.length,
        structuredData: ld.map((x) => ({
          type: x?.["@type"],
          keys: Object.keys(x || {}),
        })),
        scripts: scripts.filter((x) => !x.includes("analytics")).slice(-8),
      },
      null,
      2,
    ),
  );
}
