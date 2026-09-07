#!/usr/bin/env python3
"""Trims the JDK-duplicate java/* entries from the Robolectric android-all
jar (javax/* is kept: Android defines its own javax packages).

The android-all jar ships its own java.* classes; when that jar sits on the
ECJ classpath they shadow/conflict with the JRE's module system. One-time
cache: tools/android-all-15-trimmed.jar
"""
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename
        if name.startswith("java/"):
            continue
        zout.writestr(item, zin.read(name))
print(f"trimmed -> {dst}")
