"""Load Geofabrik Rwanda roads into FloodGuard via the authenticated GeoJSON API.

Run after importing Kigali sectors. Vulnerability values remain unset until a
verified dataset keyed to OSM road IDs is supplied.
"""
import base64
import io
import json
import os
import urllib.request
import zipfile

import shapefile

SOURCE = "https://download.geofabrik.de/africa/rwanda-latest-free.shp.zip"
API = os.getenv("FLOODGUARD_API", "http://backend:8080/api/v1/admin/roads/import")
USER = os.getenv("ADMIN_USERNAME", "admin")
PASSWORD = os.environ["ADMIN_PASSWORD"]


def post(features):
    token = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    payload = json.dumps({"type": "FeatureCollection", "features": features}).encode()
    request = urllib.request.Request(
        API,
        data=payload,
        headers={"Content-Type": "application/json", "Authorization": f"Basic {token}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)["imported"]


def main():
    print("Downloading Geofabrik Rwanda road extract…", flush=True)
    with urllib.request.urlopen(SOURCE, timeout=180) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read()))
    prefix = next((name[:-4] for name in archive.namelist() if name.endswith("gis_osm_roads_free_1.shp")), None)
    if not prefix:
        raise RuntimeError("Geofabrik archive has no expected roads shapefile")
    reader = shapefile.Reader(
        shp=io.BytesIO(archive.read(prefix + ".shp")),
        shx=io.BytesIO(archive.read(prefix + ".shx")),
        dbf=io.BytesIO(archive.read(prefix + ".dbf")),
        encoding="utf-8",
    )
    batch = []
    inserted = 0
    for feature in reader.iterShapeRecords():
        properties = feature.record.as_dict()
        batch.append({"type": "Feature", "properties": properties, "geometry": feature.shape.__geo_interface__})
        if len(batch) == 100:
            inserted += post(batch)
            batch.clear()
    if batch:
        inserted += post(batch)
    print(f"Loaded {inserted} Kigali-intersecting roads. Vulnerability scores are still unverified.")


if __name__ == "__main__":
    main()
