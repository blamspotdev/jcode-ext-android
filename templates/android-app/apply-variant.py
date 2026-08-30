#!/usr/bin/env python3
"""
Replace the app module of an SDK-scaffolded project with one of this pack's variants.

The SDK's template package ships exactly one template — `empty-activity-compose`. Everything else
the New Project gallery offers is authored here, and authored as a *diff against that one* rather
than as a project of its own: the scaffold has already produced the Gradle wrapper, the launcher
icons at six densities, a versions catalogue and a build that is known to work on this device, and
none of that is worth writing again per variant. What a variant owns is its app module.

A variant is a directory under `variants/`:

    variant.json          what to do beyond copying files (all keys optional)
    libs.additions.toml   [versions] / [libraries] entries merged into the project's catalogue
    app/…                 files copied over the project, with `PKG` standing for the package
                          directory and `__PLACEHOLDERS__` expanded in text files

Nothing here reads the *user's* answers directly: the values are taken back out of the build file
the scaffold just wrote, so a variant cannot disagree with the project it is being applied to.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

TEXT_SUFFIXES = {".kt", ".kts", ".xml", ".toml", ".pro", ".txt", ".json", ".properties"}


def read_build_values(app_build: Path) -> dict[str, str]:
    """The five facts a variant's build file needs, read from the one the scaffold wrote."""
    text = app_build.read_text(encoding="utf-8")

    def one(pattern: str, name: str) -> str:
        match = re.search(pattern, text)
        if not match:
            sys.exit(f"could not find {name} in {app_build}")
        return match.group(1)

    return {
        "__NAMESPACE__": one(r'namespace\s*=\s*"([^"]+)"', "namespace"),
        "__APPLICATION_ID__": one(r'applicationId\s*=\s*"([^"]+)"', "applicationId"),
        "__MIN_SDK__": one(r"minSdk\s*=\s*(\d+)", "minSdk"),
        "__TARGET_SDK__": one(r"targetSdk\s*=\s*(\d+)", "targetSdk"),
        "__COMPILE_SDK__": one(r"compileSdk\s*=\s*(\d+)", "compileSdk"),
    }


def clear_sources(project: Path) -> None:
    """Everything the Compose template put in the app module's source sets, and nothing else.

    Tests included: they are Compose tests, and a variant that is not Compose cannot compile them.
    The resources are left alone -- the icons, the backup rules and `strings.xml` (which carries the
    project's own name) are the scaffold's, not the template's.
    """
    for relative in ("app/src/main/java", "app/src/test", "app/src/androidTest"):
        target = project / relative
        if target.exists():
            shutil.rmtree(target)


def copy_tree(src: Path, project: Path, values: dict[str, str], package: str) -> int:
    """Copy the variant's files over the project, expanding placeholders as they go."""
    copied = 0
    for source in sorted(p for p in src.rglob("*") if p.is_file()):
        parts = list(source.relative_to(src).parts)
        # `PKG` is the package directory, which is not known until the project has one.
        if "PKG" in parts:
            at = parts.index("PKG")
            parts[at : at + 1] = package.split(".")
        target = project.joinpath(*parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix in TEXT_SUFFIXES:
            text = source.read_text(encoding="utf-8")
            for key, value in values.items():
                text = text.replace(key, value)
            target.write_text(text, encoding="utf-8", newline="\n")
        else:
            shutil.copyfile(source, target)
        copied += 1
    return copied


def merge_versions_catalogue(catalogue: Path, additions: Path) -> list[str]:
    """Add a variant's entries to `libs.versions.toml` without touching what is already there.

    Appended per section rather than rewritten, because the versions that matter most in that file
    -- the Android Gradle plugin and Kotlin -- are the SDK's answer to what this device can build,
    and a variant that shipped its own copy of them would freeze that answer at whatever it was on
    the day it was written.
    """
    if not additions.exists():
        return []
    text = catalogue.read_text(encoding="utf-8")
    added: list[str] = []
    section, lines = None, {"versions": [], "libraries": [], "plugins": []}
    for line in additions.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1]
            continue
        if not stripped or stripped.startswith("#") or section not in lines:
            continue
        key = stripped.split("=", 1)[0].strip()
        # An entry the catalogue already defines is left as it found it.
        if re.search(rf"^\s*{re.escape(key)}\s*=", text, re.MULTILINE):
            continue
        lines[section].append(line.rstrip())
        added.append(key)

    for name, new in lines.items():
        if not new:
            continue
        header = f"[{name}]"
        if header not in text:
            text = text.rstrip("\n") + f"\n\n{header}\n" + "\n".join(new) + "\n"
            continue
        at = text.index(header) + len(header)
        end = text.find("\n[", at)
        end = len(text) if end == -1 else end
        text = text[:at] + "\n" + "\n".join(new) + text[at:end].rstrip("\n") + "\n" + text[end:]
    catalogue.write_text(text, encoding="utf-8", newline="\n")
    return added


def retheme(project: Path, parent: str) -> None:
    """Repoint the scaffold's theme without renaming it.

    The style's name is derived from the project's name and the manifest already refers to it, so
    the parent is what changes. A Views project needs an AppCompat/Material ancestor: without one
    every `AppCompatActivity` throws at `onCreate` complaining about its own theme.
    """
    themes = project / "app/src/main/res/values/themes.xml"
    if not themes.exists():
        return
    text = themes.read_text(encoding="utf-8")
    themes.write_text(
        re.sub(r'parent="[^"]*"', f'parent="{parent}"', text, count=1),
        encoding="utf-8",
        newline="\n",
    )


def drop_launcher_activity(project: Path) -> None:
    manifest = project / "app/src/main/AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    text = re.sub(r"\n\s*<activity\b.*?</activity>", "", text, flags=re.DOTALL)
    manifest.write_text(text, encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--variant", required=True)
    args = parser.parse_args()

    project, variant = Path(args.project), Path(args.variant)
    app_build = project / "app/build.gradle.kts"
    if not app_build.is_file():
        sys.exit("the SDK scaffold left no app/build.gradle.kts to read the project's settings from")

    settings = json.loads((variant / "variant.json").read_text(encoding="utf-8"))
    values = read_build_values(app_build)
    package = values["__NAMESPACE__"]

    clear_sources(project)
    copied = copy_tree(variant / "app", project / "app", values, package) if (variant / "app").is_dir() else 0
    print(f"   {copied} files")

    added = merge_versions_catalogue(project / "gradle/libs.versions.toml", variant / "libs.additions.toml")
    if added:
        print(f"   catalogue: {', '.join(added)}")

    if theme := settings.get("themeParent"):
        retheme(project, theme)
        print(f"   theme parent: {theme}")

    if settings.get("dropLauncherActivity"):
        drop_launcher_activity(project)
        print("   manifest: no launcher activity")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
