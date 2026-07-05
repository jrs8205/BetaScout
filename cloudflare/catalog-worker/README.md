# betascout-catalog worker

A small read-only Cloudflare Worker that serves the beta catalog from KV.

- **Endpoint:** https://betascout-catalog.jarsi.workers.dev
- **KV namespace:** `betascout-catalog` (id `5deb2577b8524356a98468b5fb60f5df`)
- Returns the catalog JSON with `access-control-allow-origin: *` and a 1h cache.

## Update the served catalog (after a harvest)

```bash
wrangler kv key put --remote \
  --namespace-id 5deb2577b8524356a98468b5fb60f5df \
  catalog --path catalog/catalog.json
```

## Deploy

```bash
wrangler deploy -c cloudflare/catalog-worker/wrangler.jsonc
```

## TODO: auto-update from CI

The daily harvest workflow should push `catalog/catalog.json` to KV using a
scoped Cloudflare API token (a `CLOUDFLARE_API_TOKEN` repo secret with
Workers KV Storage: Edit), so the endpoint always serves fresh data without a
manual upload.
