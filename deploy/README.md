# Cloud deployment — cloud primary + home PC replica

The cloud VM is **disposable**: it is created for the event and destroyed
afterwards. The archive lives on the home PC. Both machines keep identical copies.

```
Guests ──HTTPS──▶ Tailscale Funnel ──▶ VM (primary)
                                         ├─ writes to its own disk (source of truth)
                                         ├─ pushes to the PC over the tailnet
                                         └─ reconciles every 60 s, both directions

Home PC (replica) ──▶ pushes to the VM through its public Funnel URL
```

## Prerequisites

| Thing | Where |
|---|---|
| Hetzner project + API token (Read & Write) | `.env` → `HCLOUD_TOKEN` |
| SSH key registered in Hetzner | `.env` → `HCLOUD_SSH_KEY` |
| Tailscale auth key (reusable, ephemeral, `tag:nube`) | `.env` → `TS_AUTHKEY` |
| Public GitHub repo | `.env` → `REPO_URL` |
| Shared replication secret | `.env` → `REPLICATION_TOKEN` |

`.env` is gitignored. The VM never sees this file: `cloud-init.yaml` receives the
values it needs as user-data.

## Turn it on

```powershell
.\nube-on.bat
```

Creates (or reuses) a firewall that allows only SSH and ICMP, creates the VM,
installs Docker + Tailscale, clones the repo, builds the image, starts the
service and exposes it with Funnel. Then it waits for `https://<FUNNEL_HOST>/api/health`.

Takes 3–6 minutes on a fresh VM (the Docker build dominates).

## Check it

```powershell
# Both sides must report the same count and zero divergence
curl.exe -H "X-Replication-Token: <token>" https://<FUNNEL_HOST>/api/internal/status
curl.exe -H "X-Replication-Token: <token>" http://localhost:8080/api/internal/status
```

Healthy looks like:

```json
{"peerReachable":true,"localCount":42,"peerCount":42,"missingLocally":0,
 "missingOnPeer":0,"checksumMismatches":0,"outboxDepth":0,"lastError":null}
```

## Download the server copy

```powershell
.\descargar-fotos.bat
```

Copies the whole uploads volume from the VM over SCP into
`C:\Users\PC\Pictures\fotos-nube`, mirroring the same layout as the home replica.
It downloads into a staging folder, checks the file count against what the cloud
itself reports, and only then swaps it into place — a failed download leaves the
previous copy untouched.

`nube-off.bat` runs this automatically before deleting the VM, so the server copy
is never skipped by accident.

## Turn it off (safely)

```powershell
.\nube-off.bat
```

Three gates run in order:

1. **Replication check** — refuses to continue unless **both** machines report zero
   divergence, zero outbox depth and a reachable peer. Each side reports its last
   reconciliation, so the check polls up to 5 times (20 s apart) until they agree
   instead of failing on a stale snapshot.
2. **Server copy** — downloads everything to `Pictures\fotos-nube` and verifies the
   count. If it fails, the VM stays up.
3. **Delete** — only after 1 and 2 pass. Cost drops to €0.

The firewall is kept (it is free) for the next event.

| Flag | Effect |
|---|---|
| `-SkipBackup` | Skips step 2 |
| `-Force` | Skips the checks in steps 1 and 2 |

## Failover during the event

If the cloud dies mid-event:

1. Edit `invitacion-marina-fernando/js/fotos.js` → `API_BASE_URL` to
   `https://desktop-udmt3js.tailfe106b.ts.net`.
2. Commit and push (GitHub Pages deploys).
3. Guests keep uploading to the PC.
4. When the cloud is back, the reconciler copies those photos up automatically.

## Troubleshooting

| Symptom | Check |
|---|---|
| `nube-on` times out | `ssh root@<ip> 'tail -50 /var/log/cloud-init-output.log'` |
| Health OK but Funnel 404 | `ssh root@<ip> 'tailscale funnel status'` |
| `peerReachable:false` | `tailscale status` on both machines; the PC must be on |
| `outboxDepth` stuck | the PC is unreachable; the retry backs off up to 5 min |
| `checksumMismatches` > 0 | do **not** delete the VM; compare the files by hand |
| Container cannot write | check `PC_STORAGE_PATH` exists and is not inside OneDrive |

## Costs

CX23 in `nbg1`: €0.0104/hour, monthly cap €6.49. A full weekend ≈ **€0.50**.
After `nube-off` the cost is **€0** (Hetzner bills servers while they exist, even
when powered off — that is why the script deletes instead of stopping).
