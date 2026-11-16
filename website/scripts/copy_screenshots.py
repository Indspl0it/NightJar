"""Copy the documentation screenshots from the app build to the website.

The screenshot test writes the images to app/build/outputs/docs-screens/. This script copies each PNG file to
website/src/assets/screens/. It deletes images in the target folder that the test did not write.

Usage (from the repository root):

    ./gradlew :app:recordRoborazziDebug --tests "dev.nightjar.app.screenshot.DocsScreenshotTest"
    python website/scripts/copy_screenshots.py
"""

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app" / "build" / "outputs" / "docs-screens"
TARGET = ROOT / "website" / "src" / "assets" / "screens"


def main() -> int:
    images = sorted(SOURCE.glob("*.png"))
    if not images:
        print(f"No images in {SOURCE}. Run the screenshot test first.", file=sys.stderr)
        return 1
    TARGET.mkdir(parents=True, exist_ok=True)
    names = {image.name for image in images}
    for old in TARGET.glob("*.png"):
        if old.name not in names:
            old.unlink()
            print(f"Deleted {old.name}")
    for image in images:
        shutil.copy2(image, TARGET / image.name)
    print(f"Copied {len(images)} images to {TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
