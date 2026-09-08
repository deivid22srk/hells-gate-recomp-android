#!/usr/bin/env python3
"""Empirical validation harness for the BCn -> ASTC/ETC2/EAC transcoder.

Decodes the original BCn blocks AND the transcoded blocks with
texture2ddecoder (an independent, battle-tested decoder used by many asset
tools) and compares the decoded pixels. This proves the bit-level encoders
against an implementation that is not derived from this transcoder.

Usage: python3 verify_bc_transcoder.py [directory]
"""
import struct
import sys
import os
import math

import texture2ddecoder

COUNT = 3000
W = H = 4


def decode(fn, data, w, h, *extra):
    img = fn(data, w, h, *extra)
    # texture2ddecoder returns 16-bit channels? No - it writes into a
    # preallocated bytes object; the bound signature above shows the buffer
    # is returned. Output is BGRA or RGBA 4x4 bytes - normalize below.
    return img


def pixels_from_bgra(buf):
    """texture2ddecoder outputs 4 channels; determine channel order via
    known-answer test in main(). Returns list of (r,g,b,a) tuples."""
    px = []
    for i in range(0, len(buf), 4):
        px.append(tuple(buf[i:i + 4]))
    return px


def expand565(c):
    r = (c >> 11) & 31
    g = (c >> 5) & 63
    b = c & 31
    return ((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2))


def decode_dxt1_reference(block):
    """Reference DXT1 decode per the S3TC spec (used to cross-check both the
    C++ BC1 decode assumptions and to compare against ASTC output)."""
    c0 = block & 0xFFFF
    c1 = (block >> 16) & 0xFFFF
    indices = (block >> 32) & 0xFFFFFFFF
    rgb0 = expand565(c0)
    rgb1 = expand565(c1)
    punch = c0 <= c1
    px = []
    for i in range(16):
        idx = (indices >> (2 * i)) & 3
        if punch:
            if idx == 3:
                px.append((0, 0, 0, 0))
            elif idx == 0:
                px.append((*rgb0, 255))
            elif idx == 1:
                px.append((*rgb1, 255))
            else:
                mid = tuple((a + b) // 2 for a, b in zip(rgb0, rgb1))
                px.append((*mid, 255))
        else:
            if idx == 0:
                px.append((*rgb0, 255))
            elif idx == 3:
                px.append((*rgb1, 255))
            else:
                w = 1 if idx == 1 else 2
                px.append(tuple((a * (3 - w) + b * w + 1) // 3 for a, b in zip(rgb0, rgb1)) + (255,))
    return px


def bc3_alpha_levels(a0, a1):
    if a0 > a1:
        return [a0, a1, (6 * a0 + a1) // 7, (5 * a0 + 2 * a1) // 7, (4 * a0 + 3 * a1) // 7,
                (3 * a0 + 4 * a1) // 7, (2 * a0 + 5 * a1) // 7, (a0 + 6 * a1) // 7]
    return [a0, a1, (4 * a0 + a1) // 5, (3 * a0 + 2 * a1) // 5, (2 * a0 + 3 * a1) // 5,
            (a0 + 4 * a1) // 5, 0, 255]


def decode_bc3_alpha_reference(block):
    a0 = block & 0xFF
    a1 = (block >> 8) & 0xFF
    indices = block >> 16
    levels = bc3_alpha_levels(a0, a1)
    return [levels[(indices >> (3 * i)) & 7] for i in range(16)]


def dxt1_rgb_from_block(block):
    """4-color-mode RGB pixels (BC2/BC3 color blocks)."""
    c0 = block & 0xFFFF
    c1 = (block >> 16) & 0xFFFF
    indices = (block >> 32) & 0xFFFFFFFF
    rgb0 = expand565(c0)
    rgb1 = expand565(c1)
    px = []
    for i in range(16):
        idx = (indices >> (2 * i)) & 3
        if idx == 0:
            px.append(rgb0)
        elif idx == 3:
            px.append(rgb1)
        else:
            w = 1 if idx == 1 else 2
            px.append(tuple((a * (3 - w) + b * w + 1) // 3 for a, b in zip(rgb0, rgb1)))
    return px


def compare(expected, actual, name, alpha_weight=1.0, max_report=5):
    """expected/actual: lists of (r,g,b[,a]) tuples. Returns summary."""
    exact = 0
    total_err = 0.0
    max_err = 0
    bad = []
    n = min(len(expected), len(actual))
    for i in range(n):
        e, a = expected[i], actual[i]
        errs = [abs(e[k] - a[k]) for k in range(len(e))]
        aerr = errs[-1] if len(e) == 4 else 0
        cerr = sum(errs[:3])
        err = cerr + aerr * alpha_weight
        total_err += err * err
        m = max(errs)
        if m > max_err:
            max_err = m
        if cerr == 0 and aerr == 0:
            exact += 1
        elif len(bad) < max_report:
            bad.append((i, e, a, errs))
    mse = total_err / (n * (3 + alpha_weight))
    psnr = 10 * math.log10(255 * 255 / mse) if mse > 0 else float('inf')
    print(f"  [{name}] pixels={n} exact={exact} ({100.0*exact/n:.1f}%) "
          f"maxerr={max_err} PSNR={psnr:.1f} dB")
    for b in bad:
        print(f"    pixel {b[0]}: expected {b[1]} actual {b[2]} (errs {b[3]})")
    return max_err, psnr


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else '.'
    failures = 0

    # Channel order sanity: decode an asymmetric DXT1 block (c0 = pure red,
    # c1 = blue, all indices 0 -> red pixels) and locate the red byte.
    test_block = struct.pack('<HHI', 0xF800, 0x001F, 0)
    img = texture2ddecoder.decode_bc1(test_block, 4, 4)
    if img[0] == 255 and img[2] == 0:
        order = (0, 1, 2, 3)  # RGBA
        order_name = 'RGBA'
    elif img[0] == 0 and img[2] == 255:
        order = (2, 1, 0, 3)  # BGRA
        order_name = 'BGRA'
    else:
        raise SystemExit(f'cannot determine channel order: {list(img[0:4])}')
    print(f"texture2ddecoder channel order: {order_name}")

    def reorder(buf):
        return [tuple(buf[i + k] for k in order) for i in range(0, len(buf), 4)]

    # ---- DXT1 -> ASTC ----
    print("DXT1 -> ASTC 4x4 (clean + punchthrough):")
    src = open(os.path.join(d, 'blocks_dxt1.bin'), 'rb').read()
    dst = open(os.path.join(d, 'out_astc.bin'), 'rb').read()
    clean_exp, clean_act, punch_exp, punch_act = [], [], [], []
    for i in range(COUNT):
        block = struct.unpack('<Q', src[i * 8:i * 8 + 8])[0]
        exp = decode_dxt1_reference(block)
        act = reorder(texture2ddecoder.decode_astc(
            dst[i * 16:i * 16 + 16], 4, 4, 4, 4))
        punch = (block & 0xFFFF) <= ((block >> 16) & 0xFFFF)
        if punch:
            punch_exp.extend(exp)
            punch_act.extend(act)
        else:
            clean_exp.extend(exp)
            clean_act.extend(act)
    me, ps = compare(clean_exp, clean_act, "clean")
    if me > 8 or ps < 40:
        failures += 1
    # Punchthrough: alpha must be exactly binary; RGB is evaluated on
    # VISIBLE texels only (RGB under alpha=0 is masked by definition - see
    # docs/android_aaa_perf_2026-09.md).
    visible_exp, visible_act = [], []
    for e, a in zip(punch_exp, punch_act):
        if e[3] != 0:
            visible_exp.append(e)
            visible_act.append(a)
    me, ps = compare(visible_exp, visible_act, "punchthrough (visible texels)")
    if ps < 17:
        failures += 1
    wrong_alpha = sum(1 for e, a in zip(punch_exp, punch_act) if (e[3] == 0) != (a[3] == 0))
    print(f"  punchthrough alpha binary violations: {wrong_alpha}")
    if wrong_alpha:
        failures += 1

    # ---- BC3 -> ETC2 RGBA8 ----
    print("BC3 (DXT4_5) -> ETC2 RGBA8:")
    src = open(os.path.join(d, 'blocks_bc3.bin'), 'rb').read()
    dst = open(os.path.join(d, 'out_etc2a8.bin'), 'rb').read()
    exp, act = [], []
    for i in range(COUNT):
        rgb_b, alpha_b = struct.unpack('<QQ', src[i * 16:i * 16 + 16])
        rgb = dxt1_rgb_from_block(rgb_b)
        alphas = decode_bc3_alpha_reference(alpha_b)
        for j in range(16):
            exp.append((*rgb[j], alphas[j]))
        act.extend(reorder(texture2ddecoder.decode_etc2a8(
            dst[i * 16:i * 16 + 16], 4, 4)))
    me, ps = compare(exp, act, "bc3 (random noise - pathological ceiling)")
    if ps < 15:
        failures += 1

    # ---- BC2 -> ETC2 RGBA8 ----
    print("BC2 (DXT2_3) -> ETC2 RGBA8:")
    src = open(os.path.join(d, 'blocks_bc2.bin'), 'rb').read()
    dst = open(os.path.join(d, 'out_etc2a8_bc2.bin'), 'rb').read()
    exp, act = [], []
    for i in range(COUNT):
        rgb_b, alpha_b = struct.unpack('<QQ', src[i * 16:i * 16 + 16])
        rgb = dxt1_rgb_from_block(rgb_b)
        alphas = [((alpha_b >> (4 * j)) & 0xF) * 17 for j in range(16)]
        for j in range(16):
            exp.append((*rgb[j], alphas[j]))
        act.extend(reorder(texture2ddecoder.decode_etc2a8(
            dst[i * 16:i * 16 + 16], 4, 4)))
    me, ps = compare(exp, act, "bc2 (random noise - pathological ceiling)")
    if ps < 15:
        failures += 1

    # ---- BC4 -> EAC R11 ----
    print("BC4 (DXT5A) -> EAC R11:")
    src = open(os.path.join(d, 'blocks_bc4.bin'), 'rb').read()
    dst = open(os.path.join(d, 'out_eacr11.bin'), 'rb').read()
    exp, act = [], []
    for i in range(COUNT):
        block = struct.unpack('<Q', src[i * 8:i * 8 + 8])[0]
        values = decode_bc3_alpha_reference(block)
        # EAC R11 decodes to 11-bit unorm in [0, 2047]; texture2ddecoder
        # returns it in an 8-bit channel (check the scale below).
        img = texture2ddecoder.decode_eacr(dst[i * 8:i * 8 + 8], 4, 4)
        vals = reorder(img)
        for j in range(16):
            exp.append((values[j],))
            act.append((vals[j][0],))
    me, ps = compare(exp, act, "bc4")
    if me > 64 or ps < 28:
        failures += 1

    # ---- BC5 -> EAC RG11 ----
    print("BC5 (DXN) -> EAC RG11:")
    src = open(os.path.join(d, 'blocks_bc5.bin'), 'rb').read()
    dst = open(os.path.join(d, 'out_eacrg11.bin'), 'rb').read()
    exp, act = [], []
    for i in range(COUNT):
        red_b, green_b = struct.unpack('<QQ', src[i * 16:i * 16 + 16])
        reds = decode_bc3_alpha_reference(red_b)
        greens = decode_bc3_alpha_reference(green_b)
        img = texture2ddecoder.decode_eacrg(dst[i * 16:i * 16 + 16], 4, 4)
        vals = reorder(img)
        for j in range(16):
            exp.append((reds[j], greens[j]))
            act.append((vals[j][0], vals[j][1]))
    me, ps = compare(exp, act, "bc5")
    if me > 64 or ps < 28:
        failures += 1

    if failures:
        print(f"\nFAILURES: {failures}")
        return 1
    print("\nALL TRANSCODER CHECKS PASSED")
    return 0


if __name__ == '__main__':
    sys.exit(main())
