#!/usr/bin/env python3
"""Generate the offline map asset from OSM federal-district GeoJSON boundaries."""
import gzip
import json
import sys
from pathlib import Path

REGION_SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/russian-regions.js")
DISTRICT_DIR = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/tmp/russia-federal-districts")
MEMBERSHIP_SOURCE = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("tools/russia-federal-districts.json")
DEST = Path(sys.argv[4]) if len(sys.argv) > 4 else Path("app/src/main/assets/russia-federal-districts.mapdata")
SIMPLIFY_DEGREES = 0.015
VIEW_WIDTH = 1000.0
VIEW_HEIGHT = 390.0


def normalize(value):
    return "".join(c for c in value.lower().replace("ё", "е") if c.isalnum())


def region_core(value):
    raw = value.lower().replace("ё", "е")
    if "санкт-петербург" in raw:
        return "санктпетербург"
    if "москва" in raw:
        return "москва"
    if "ханты-мансийский" in raw:
        return "хантымансийский"
    s = normalize(value)
    for old, new in (
        ("столицаРоссийскойФедерации", ""),
        ("федеральногозначения", ""),
        ("автономныйокруг", "ао"),
        ("автономнаяобласть", "аобл"),
        ("республика", ""),
        ("область", "обл"),
    ):
        s = s.replace(normalize(old), normalize(new))
    s = s.replace("югра", "")
    for suffix in ("респ", "обл", "аобл", "ао", "город", "г"):
        if s.endswith(suffix):
            s = s[:-len(suffix)]
    if s in ("севернаяосетия", "севернаяосетияалания"):
        s = "севернаяосетияалания"
    return s.replace("башкирия", "башкортостан").replace("тува", "тыва")


def rings_of(geometry):
    if geometry["type"] == "Polygon":
        return geometry["coordinates"]
    if geometry["type"] == "MultiPolygon":
        return [ring for polygon in geometry["coordinates"] for ring in polygon]
    return []


def simplify(points, tolerance):
    if len(points) <= 4:
        return points
    sq_tol = tolerance * tolerance
    keep = {0, len(points) - 1}
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        ax, ay = points[first][:2]
        bx, by = points[last][:2]
        dx, dy = bx - ax, by - ay
        denom = dx * dx + dy * dy
        best_i, best_dist = None, sq_tol
        for i in range(first + 1, last):
            px, py = points[i][:2]
            if denom:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / denom))
                dist = (px - (ax + t * dx)) ** 2 + (py - (ay + t * dy)) ** 2
            else:
                dist = (px - ax) ** 2 + (py - ay) ** 2
            if dist > best_dist:
                best_i, best_dist = i, dist
        if best_i is not None:
            keep.add(best_i)
            stack.extend(((first, best_i), (best_i, last)))
    result = [points[i] for i in sorted(keep)]
    return result if len(result) >= 4 else points


def load_geojson(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    region_source = REGION_SOURCE.read_text(encoding="utf-8")
    region_source = region_source[region_source.index("{"):].strip().rstrip(";")
    regions = json.loads(region_source)["features"]
    by_core = {}
    for feature in regions:
        by_core.setdefault(region_core(feature["properties"]["name"]), []).append(feature)

    membership = json.loads(MEMBERSHIP_SOURCE.read_text(encoding="utf-8"))["data"]
    district_for_region = {}
    for district in membership:
        for area in district["areas"]:
            matches = by_core.get(region_core(area["name"]), [])
            if len(matches) != 1:
                raise ValueError(f"Cannot match {area['name']}: {len(matches)} map features")
            district_for_region[matches[0]["properties"]["name"]] = district["name"]

    crimea_names = {"Республика Крым", "Город федерального значения Севастополь"}
    region_names = {feature["properties"]["name"] for feature in regions}
    if set(district_for_region) | crimea_names != region_names:
        raise ValueError(f"Unassigned map regions: {sorted(region_names - set(district_for_region) - crimea_names)}")

    district_geometries = {}
    for path in sorted(DISTRICT_DIR.glob("*.geojson")):
        collection = load_geojson(path)
        for feature in collection["features"]:
            district_name = feature["properties"]["Federal District"]
            district_geometries[district_name] = rings_of(feature["geometry"])
    if len(district_geometries) != 8:
        raise ValueError(f"Expected 8 district boundary files, found {len(district_geometries)}")

    crimea_rings = [ring for feature in regions if feature["properties"]["name"] in crimea_names
                    for ring in rings_of(feature["geometry"])]
    all_points = [point for rings in district_geometries.values() for ring in rings for point in ring]
    all_points.extend(point for ring in crimea_rings for point in ring)
    all_lons = [point[0] if point[0] >= 0 else point[0] + 360 for point in all_points]
    min_lon = min(all_lons)
    max_lon = max(all_lons)
    min_lat = min(p[1] for p in all_points)
    max_lat = max(p[1] for p in all_points)

    def project(point):
        lon, lat = point[:2]
        if lon < 0:
            lon += 360
        return ((lon - min_lon) / (max_lon - min_lon) * VIEW_WIDTH,
                (max_lat - lat) / (max_lat - min_lat) * VIEW_HEIGHT)

    def encode(rings):
        output = []
        for ring in rings:
            coords = [project(point) for point in simplify(ring, SIMPLIFY_DEGREES)]
            if len(coords) >= 4:
                output.append(";".join(f"{x:.1f},{y:.1f}" for x, y in coords))
        return "|".join(output)

    lines = []
    for name, rings in district_geometries.items():
        lines.append(f"D\t{name}\t{encode(rings)}")
    lines.append("C\tКрым и Севастополь\t" + encode(crimea_rings))
    for region_name, district_name in district_for_region.items():
        lines.append(f"R\t{region_name}\t{district_name}")

    DEST.parent.mkdir(parents=True, exist_ok=True)
    with DEST.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=9) as zipped:
            zipped.write(("\n".join(lines) + "\n").encode("utf-8"))
    print(f"wrote {len(district_geometries)} districts to {DEST} ({DEST.stat().st_size} bytes gzipped)")


if __name__ == "__main__":
    main()
