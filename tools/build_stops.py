#!/usr/bin/env python3
"""Génère app/src/main/assets/stops_index.json à partir du GTFS Ilévia :
pour chaque ligne et chaque sens, la liste ordonnée de TOUS les arrêts desservis (toutes branches), avec la commune."""
import collections, csv, io, json, os, re, sys, urllib.request, zipfile

URL = "https://media.ilevia.fr/opendata/gtfs.zip"
OUT = "app/src/main/assets/stops_index.json"

def main():
    data = urllib.request.urlopen(urllib.request.Request(URL, headers={"User-Agent": "ilevia-departs-build"}), timeout=180).read()
    z = zipfile.ZipFile(io.BytesIO(data))
    rows = lambda n: csv.DictReader(io.TextIOWrapper(z.open(n), encoding="utf-8-sig"))

    routes = {r["route_id"]: r["route_short_name"].strip() for r in rows("routes.txt")}
    raw = {}
    for s in rows("stops.txt"):
        m = re.search(r"\b\d{5}\s+(.+)$", s.get("stop_desc") or "")
        commune = m.group(1) if m else ""
        # « TOURCOING - A L'OPPOSÉ DU N° 45 » → « TOURCOING » : on ne garde que la ville, sans les consignes de position.
        commune = re.split(r"\s+-\s+|\s*\(", commune)[0]
        commune = re.sub(r"\s+", " ", commune).strip()
        name = re.sub(r"\s+", " ", s["stop_name"]).strip()
        raw[s["stop_id"]] = (name, commune, s.get("parent_station") or "")

    table, index = [], {}
    def stop_idx(sid):
        name, commune, parent = raw.get(sid, (None, "", ""))
        if name is None:
            return None
        if not commune and parent in raw:
            commune = raw[parent][1]
        key = (name.lower(), commune.lower())
        if key not in index:
            index[key] = len(table)
            table.append([name, commune])
        return index[key]

    trips = {}
    for t in rows("trips.txt"):
        trips[t["trip_id"]] = (routes.get(t["route_id"]), t.get("direction_id") or "0", (t.get("trip_headsign") or "").strip())

    seqs = collections.defaultdict(list)
    for r in rows("stop_times.txt"):
        seqs[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"]))

    patterns = collections.defaultdict(set)
    heads = collections.defaultdict(set)
    for tid, seq in seqs.items():
        info = trips.get(tid)
        if not info or not info[0]:
            continue
        line, d, h = info
        ids = []
        for _, sid in sorted(seq):
            i = stop_idx(sid)
            if i is not None and (not ids or ids[-1] != i):
                ids.append(i)
        patterns[(line, d)].add(tuple(ids))
        if h:
            heads[(line, d)].add(h)

    lines = collections.defaultdict(list)
    for (line, d), pats in sorted(patterns.items()):
        order = []
        for p in sorted(pats, key=lambda p: -len(p)):
            prev = None
            for s in p:
                if s not in order:
                    order.insert(order.index(prev) + 1 if prev in order else len(order), s)
                prev = s
        lines[line].append({"h": sorted(heads[(line, d)]), "s": order})

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"stops": table, "lines": lines}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"::notice::stops_index: {len(table)} arrêts, {len(lines)} lignes, {os.path.getsize(OUT)//1024} Ko")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # le build continue sans index (l'app retombe sur les données temps réel)
        print(f"::warning::stops_index non généré: {e}")
        sys.exit(0)
