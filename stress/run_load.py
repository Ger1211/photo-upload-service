import argparse
import csv
import json
import os
import random
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "fixtures")
FOTO_FILES = sorted(f for f in os.listdir(FIXTURES) if f.startswith("photo_") and f.endswith(".jpg"))
RESULTS_CSV = os.path.join(HERE, "results.csv")
STATS_CSV = os.path.join(HERE, "docker_stats.csv")

results = []
results_lock = threading.Lock()
stop_event = threading.Event()
health_failures = []
stats_lock = threading.Lock()


def load_file_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def do_upload(url, event, guest, photos, timeout=180):
    payload = [("files", (os.path.basename(p), open(p, "rb"), "image/jpeg")) for p in photos]
    params = {"event": event, "guestName": guest}
    t0 = time.perf_counter()
    try:
        r = requests.post(url, files=payload, params=params, timeout=timeout)
        dt = (time.perf_counter() - t0) * 1000
        body = {}
        try:
            body = r.json()
        except Exception:
            pass
        return {
            "guest": guest,
            "status": r.status_code,
            "ms": dt,
            "uploaded": body.get("uploaded", 0),
            "failed": body.get("failed", 0),
            "files": len(photos),
            "bytes": sum(os.path.getsize(p) for p in photos),
        }
    except Exception as e:
        return {
            "guest": guest,
            "status": 0,
            "ms": (time.perf_counter() - t0) * 1000,
            "uploaded": 0,
            "failed": len(photos),
            "files": len(photos),
            "bytes": sum(os.path.getsize(p) for p in photos),
            "error": str(e),
        }


def steady_worker(url, event, guest, files, max_duration):
    rnd = random.Random()
    while not stop_event.is_set():
        n = rnd.randint(3, 5)
        photos = rnd.sample(files, min(n, len(files)))
        res = do_upload(url, event, guest, photos)
        with results_lock:
            results.append(res)
        if stop_event.is_set():
            break
        time.sleep(rnd.uniform(2.0, 6.0))


def burst_worker(url, event, guest, files):
    n = random.randint(3, 5)
    photos = random.sample(files, min(n, len(files)))
    res = do_upload(url, event, guest, photos)
    with results_lock:
        results.append(res)


def health_checker(url):
    while not stop_event.is_set():
        try:
            r = requests.get(url, timeout=5)
            if r.status_code != 200:
                health_failures.append((time.time(), r.status_code))
        except Exception as e:
            health_failures.append((time.time(), str(e)))
        time.sleep(2.0)


def stats_sampler(container, interval=2.0):
    while not stop_event.is_set():
        try:
            out = subprocess.run(
                ["docker", "stats", "--no-stream", "--format",
                 "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}", container],
                capture_output=True, text=True, timeout=15, check=True).stdout.strip()
            with stats_lock:
                with open(STATS_CSV, "a", newline="") as f:
                    f.write(f"{time.time()},{out}\n")
        except Exception as e:
            with stats_lock:
                with open(STATS_CSV, "a", newline="") as f:
                    f.write(f"{time.time()},ERROR,{e}\n")
        time.sleep(interval)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p / 100
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def run_steady(url, event, guests, duration):
    files = [os.path.join(FIXTURES, f) for f in FOTO_FILES]
    print(f"[steady] {guests} guests, {duration}s, files each req 3-5")
    with ThreadPoolExecutor(max_workers=guests) as ex:
        futures = [ex.submit(steady_worker, url, event, f"guest-{i:03d}", files, duration)
                   for i in range(guests)]
        progress = threading.Thread(target=progress_printer, daemon=True)
        progress.start()
        time.sleep(duration)
        stop_event.set()
        for f in futures:
            f.result(timeout=300)
    print(f"[steady] finished")


def progress_printer():
    last = 0
    while not stop_event.is_set():
        time.sleep(10)
        with results_lock:
            n = len(results)
        print(f"[progress] {n} requests completed (+{n - last})")
        last = n


def run_burst(url, event, requests_count):
    files = [os.path.join(FIXTURES, f) for f in FOTO_FILES]
    print(f"[burst] {requests_count} requests at once, 3-5 files each")
    with ThreadPoolExecutor(max_workers=requests_count) as ex:
        futures = [ex.submit(burst_worker, url, event, f"guest-{i:04d}", files)
                   for i in range(requests_count)]
        for f in as_completed(futures):
            f.result(timeout=300)
    print(f"[burst] all {requests_count} done")


def integrity_check(uploads_root, event):
    base = os.path.join(uploads_root, event)
    photo_files = 0
    thumb_files = 0
    manifest_entries = 0
    if os.path.isdir(base):
        for dirpath, dirnames, filenames in os.walk(base):
            rel = os.path.relpath(dirpath, base)
            top = rel.split(os.sep)[0]
            for name in filenames:
                if name in ("manifest.jsonl", "manifest.json"):
                    try:
                        path = os.path.join(dirpath, name)
                        if name == "manifest.jsonl":
                            with open(path, encoding="utf-8") as f:
                                manifest_entries += sum(1 for ln in f if ln.strip())
                        else:
                            with open(path, encoding="utf-8") as f:
                                manifest_entries += len(json.load(f))
                    except Exception as e:
                        print(f"[integrity] ERROR reading manifest {os.path.join(dirpath, name)}: {e}")
                    continue
                if top == "thumbnails":
                    thumb_files += 1
                else:
                    photo_files += 1
    print(f"[integrity] photos_on_disk={photo_files} thumbs_on_disk={thumb_files} manifest_entries={manifest_entries}")
    if photo_files != manifest_entries:
        print(f"[integrity] *** MISMATCH: photos={photo_files} vs manifest={manifest_entries} "
              f"(lost/invalid entries={manifest_entries - photo_files})")
    return photo_files, manifest_entries


def report(label, start, total_bytes):
    with results_lock:
        ok = [r for r in results if r["status"] == 200]
        statuses = {}
        for r in results:
            statuses[r["status"]] = statuses.get(r["status"], 0) + 1
        lats = sorted(r["ms"] for r in ok)
        total_up = sum(r["uploaded"] for r in ok)
        total_fail = sum(r["failed"] for r in ok)
        errors = [r.get("error") for r in results if r.get("error")]
    elapsed = time.time() - start
    print(f"\n=== {label} ===")
    print(f"requests: {len(results)} | statuses: {statuses} | elapsed: {elapsed:.1f}s")
    print(f"throughput: {len(ok) / elapsed:.2f} req/s | {total_bytes / elapsed / 1024 / 1024:.2f} MB/s")
    print(f"files: uploaded={total_up} failed={total_fail}")
    if lats:
        print(f"latency(ms) p50={percentile(lats, 50):.0f} p95={percentile(lats, 95):.0f} "
              f"p99={percentile(lats, 99):.0f} max={lats[-1]:.0f}")
    if errors:
        print(f"errors: {len(errors)} first={errors[0]}")
    if health_failures:
        print(f"HEALTH FAILURES during run: {len(health_failures)} -> {health_failures[:5]}")
    else:
        print("health: OK all checks passed")
    with open(RESULTS_CSV, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["guest", "status", "ms", "uploaded", "failed", "files", "bytes"])
        w.writeheader()
        w.writerows(results)


def summarize_stats():
    if not os.path.exists(STATS_CSV):
        print("no docker stats captured")
        return
    rows = []
    with open(STATS_CSV) as f:
        for line in f:
            parts = line.strip().split("|")
            if len(parts) >= 4 and parts[1] != "ERROR":
                try:
                    cpu = float(parts[1].replace("%", ""))
                    mem_used_mb = float(parts[2].split("/")[0].replace("MiB", "").replace("GiB", ""))
                    mem_pct = float(parts[3].replace("%", ""))
                    rows.append((cpu, mem_used_mb, mem_pct))
                except (ValueError, IndexError):
                    continue
    if rows:
        cpus = sorted(r[0] for r in rows)
        mems = sorted(r[2] for r in rows)
        print(f"docker cpu% max={max(r[0] for r in rows):.1f} avg={sum(r[0] for r in rows)/len(rows):.1f}")
        print(f"docker mem% max={max(r[2] for r in rows):.1f} avg={sum(r[2] for r in rows)/len(rows):.1f} "
              f"(p95={percentile(mems, 95):.1f})")
        print(f"docker mem used max={max(r[1] for r in rows):.0f} MiB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", choices=["steady", "burst"], required=True)
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--event", default="stress-test")
    ap.add_argument("--guests", type=int, default=50)
    ap.add_argument("--requests", type=int, default=100)
    ap.add_argument("--duration", type=int, default=90)
    ap.add_argument("--container", default="photo-upload-backend")
    ap.add_argument("--uploads-root", default="uploads")
    ap.add_argument("--health", action="store_true")
    args = ap.parse_args()

    url = args.url.rstrip("/")
    upload_url = url + "/api/upload"
    health_url = url + "/api/health"

    if args.health:
        try:
            r = requests.get(health_url, timeout=5)
            print(f"precheck health: {r.status_code} {r.text[:50]}")
        except Exception as e:
            print(f"precheck FAILED: {e}")
            sys.exit(1)

    if os.path.exists(STATS_CSV):
        os.remove(STATS_CSV)
    start = time.time()
    total_bytes = 0
    stop_event.clear()

    with results_lock:
        results.clear()
    health_failures.clear()

    sampler = threading.Thread(target=stats_sampler, args=(args.container,), daemon=True)
    sampler.start()
    hcheck = None
    if args.health:
        hcheck = threading.Thread(target=health_checker, args=(health_url,), daemon=True)
        hcheck.start()

    if args.scenario == "steady":
        run_steady(upload_url, args.event, args.guests, args.duration)
    else:
        run_burst(upload_url, args.event, args.requests)

    stop_event.set()
    total_bytes = sum(r["bytes"] for r in results)
    report(f"scenario={args.scenario} guests={args.guests} dur={args.duration}", start, total_bytes)
    summarize_stats()
    integrity_check(args.uploads_root, args.event)


if __name__ == "__main__":
    main()