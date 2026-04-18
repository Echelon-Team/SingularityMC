#!/usr/bin/env python3
"""Build installer icons from launcher Logo.

Konwersja białego tła na alpha + produkcja dwóch artefaktów:

* ``installer/singularitymc.png`` — 256×256 RGBA, używany przez AppImage
  (AppRun + ``.desktop`` z ``Icon=singularitymc``). Bez alpha tło ikona w
  GNOME/KDE menu renderuje się na białym prostokącie, co wygląda obco na
  dark themes.
* ``installer/icon.ico`` — multi-res 16/32/48/64/128/256, używany przez
  Inno Setup jako ``SetupIconFile`` + ``UninstallDisplayIcon`` + osadzony
  w ``auto-update.exe`` przez shortcut. Windows picks best size
  kontekstowo; bez multi-res pixel-art small icon przy scale.

White-to-alpha:
    whiteness = min(R, G, B). Pixels powyżej ``WHITE_THRESHOLD`` stają
    się w pełni transparent, pixels w zakresie ``[EDGE_THRESHOLD,
    WHITE_THRESHOLD]`` dostają liniowe fade na alpha — zachowuje
    antialiased krawędzie zamiast rysować zębatą granicę.

Uruchom z repo root::

    python scripts/build-icons.py

Deps: Pillow + numpy (dev machine; skrypt NIE jest wołany z CI — ikony
są commitowane jako pre-built assets. Rerun tylko gdy `Logo.png`
source się zmieni).
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

SRC = Path("singularity-launcher/src/main/resources/icons/Logo.png")
DST_PNG = Path("installer/singularitymc.png")
DST_ICO = Path("installer/icon.ico")

# Pixel jest "prawie biały" gdy min(R,G,B) > WHITE_THRESHOLD → alpha 0.
# Wartości w [EDGE_THRESHOLD, WHITE_THRESHOLD] dostają proporcjonalny
# fade na alpha żeby antialiased krawędzie logo się zachowały.
WHITE_THRESHOLD = 245
EDGE_THRESHOLD = 200

# Rozmiary w multi-res ICO. 16+32+48 to klasyczne Windows Explorer; 64
# dla jump lists; 128+256 dla large icons / high-DPI.
ICO_SIZES = [16, 32, 48, 64, 128, 256]

# Docelowy rozmiar PNG dla AppImage. FreeDesktop icon spec rekomenduje
# 256x256 dla high-DPI desktop integration; smaller je downscale w locie.
APPIMAGE_SIZE = 256


def whitewash_to_alpha(img: Image.Image) -> Image.Image:
    """Zamień biały background na alpha.

    Używa numpy dla vectorized operations — pure-Python per-pixel loop
    na 1024x1024 zajmuje dziesiątki sekund, numpy wykonuje to samo pod
    sekundą.
    """
    arr = np.array(img.convert("RGBA"))
    r, g, b, a = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2], arr[:, :, 3]
    whiteness = np.minimum(np.minimum(r, g), b)
    new_alpha = a.astype(np.int32)

    # Full transparent dla near-pure-white.
    new_alpha[whiteness > WHITE_THRESHOLD] = 0

    # Linear fade dla edge pixels. fade = 0 gdy whiteness == WHITE_THRESHOLD,
    # fade = 255 gdy whiteness == EDGE_THRESHOLD.
    edge_mask = (whiteness >= EDGE_THRESHOLD) & (whiteness <= WHITE_THRESHOLD)
    fade = (
        (WHITE_THRESHOLD - whiteness.astype(np.int32))
        * 255
        // (WHITE_THRESHOLD - EDGE_THRESHOLD)
    )
    fade = np.clip(fade, 0, 255)

    # `np.minimum` żeby nie "resurrect" pixeli które były już częściowo
    # transparent w source (np. jakiś edge-soft brush w logo).
    new_alpha = np.where(edge_mask, np.minimum(new_alpha, fade), new_alpha)
    new_alpha = np.clip(new_alpha, 0, 255).astype(np.uint8)
    arr[:, :, 3] = new_alpha
    return Image.fromarray(arr, "RGBA")


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"source missing: {SRC}")

    src = Image.open(SRC)
    print(f"source: {SRC} {src.size} {src.mode}")

    alpha_full = whitewash_to_alpha(src)

    DST_PNG.parent.mkdir(parents=True, exist_ok=True)
    png = alpha_full.resize((APPIMAGE_SIZE, APPIMAGE_SIZE), Image.LANCZOS)
    png.save(DST_PNG, format="PNG")
    print(f"wrote: {DST_PNG} {APPIMAGE_SIZE}x{APPIMAGE_SIZE}")

    # PIL sam downsampluje do każdego rozmiaru w sizes= liście.
    alpha_full.save(DST_ICO, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    print(f"wrote: {DST_ICO} sizes={ICO_SIZES}")


if __name__ == "__main__":
    main()
