// Runs eec-rates.local.test.ts against the website's own source without adding anything to its checkout: the website
// is the root (its "@" alias, its node_modules), the test stays here. From the website's checkout:
//
//   EEC_RECIPES=<mod>/build/eec/devgame-website.json EEC_CASES=<cases.json> EEC_RATES_OUT=<out.json> \
//     node node_modules/vitest/vitest.mjs run --config <mod>/tools/dev/eec/vitest.eec.config.mjs
//
// FACTORY_FLOW names the website's checkout when it is not C:/Users/jack/gtnh-factory-flow.
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const site = process.env.FACTORY_FLOW ?? "C:/Users/jack/gtnh-factory-flow";

export default {
  root: site,
  resolve: { alias: { "@": path.join(site, "src") } },
  server: { fs: { allow: [site, here] } },
  test: {
    environment: "node",
    globals: true,
    dir: here,
    include: [path.join(here, "*.local.test.ts").replace(/\\/g, "/")],
    exclude: ["**/node_modules/**"],
  },
};
