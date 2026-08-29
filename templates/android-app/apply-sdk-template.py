#!/usr/bin/env python3
"""Scaffold a project from one of the Android SDK's own project templates.

The `build;templates` SDK package ships Android Studio's project templates, and they are not a
wizard framework you would have to embed — each one is a real project tree plus a
`.template/template-definition.json` that says how to turn it into somebody's project. That makes it
usable here, and worth using: Google maintains it, so it tracks AGP and Compose versions without
anybody in this repository having to.

The definition is small. Arguments with defaults, a list of SDK packages it needs, and an ordered
list of transformations of three kinds — `string-replace`, `rename-file`, and `comment`, which is an
annotation rather than an operation. Everything is expressed against the project root, and the
`${...}` expressions use a handful of named conversions.

Ordered, and applied in order against the evolving tree: one transformation renames
`local.properties.template` to `local.properties` and a later one edits `/local.properties`. Doing
them in any other order silently edits a file that is not there yet.

Python because the runtime has `python3` and does not have `jq` or even `unzip`; this needs JSON and
a zip, and the standard library has both.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from pathlib import Path

# Names the definition may call on an argument. Every one of these appears in the templates Google
# ships today; an unknown one is an error rather than a silent empty string, because a template that
# half-applied is worse than one that refused.
JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "in", "is", "object", "fun", "val", "var", "when",
}


def to_android_package_segment(value: str) -> str:
    """`My Application` → `myapplication`: what a package segment may contain, and nothing else."""
    return "".join(c for c in value.lower() if c.isalnum())


def to_java_package_segment(value: str) -> str:
    """The same, but never a digit first and never a reserved word — either is a syntax error."""
    segment = to_android_package_segment(value)
    if not segment:
        return "app"
    if segment[0].isdigit() or segment in JAVA_KEYWORDS:
        return "_" + segment
    return segment


def to_java_class_name(value: str) -> str:
    """`My Application` → `MyApplication`, for a theme or a class that is named after the app."""
    parts = re.split(r"[^A-Za-z0-9]+", value)
    name = "".join(p[:1].upper() + p[1:] for p in parts if p)
    if not name:
        return "App"
    return "_" + name if name[0].isdigit() else name


def to_java_property_value(value: str) -> str:
    """Escape for a `.properties` file, where `\\` and `:` are syntax rather than characters."""
    return value.replace("\\", "\\\\").replace(":", "\\:")


CONVERSIONS = {
    "toAndroidPackageSegment": to_android_package_segment,
    "toJavaPackageSegment": to_java_package_segment,
    "toJavaClassName": to_java_class_name,
    "toJavaPropertyValue": to_java_property_value,
}

# `${name}`, `${name.toJavaClassName()}`, `${namespace.replace('.','/')}`
EXPRESSION = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z]+\([^)]*\))*)\}")
CALL = re.compile(r"\.([A-Za-z]+)\(([^)]*)\)")
STRING_ARG = re.compile(r"""\s*'([^']*)'\s*|\s*"([^"]*)"\s*""")


def expand(text: str, values: dict[str, str]) -> str:
    """Substitutes every `${...}` in *text*, applying any conversions the expression chains on."""

    def one(match: re.Match[str]) -> str:
        name, calls = match.group(1), match.group(2)
        if name not in values:
            raise KeyError(f"the template refers to an argument nothing defines: {name}")
        value = values[name]
        for call in CALL.finditer(calls or ""):
            fn, raw = call.group(1), call.group(2)
            if fn == "replace":
                args = [a or b for a, b in STRING_ARG.findall(raw)]
                if len(args) != 2:
                    raise ValueError(f"replace() wants two strings, got: {raw!r}")
                value = value.replace(args[0], args[1])
            elif fn in CONVERSIONS:
                value = CONVERSIONS[fn](value)
            else:
                raise ValueError(f"the template uses a conversion this does not implement: {fn}()")
        return value

    return EXPRESSION.sub(one, text)


def glob_to_regex(pattern: str) -> re.Pattern[str]:
    """A selector glob, against a project-relative POSIX path.

    A leading `/` means the project root; `**` crosses directories and `*` does not. Written out by
    hand rather than with fnmatch, which has no `**` and would let `/app/build.gradle.kts` match a
    file of that name in any subdirectory.
    """
    pattern = pattern.lstrip("/")
    out = ["^"]
    i = 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    out.append("$")
    return re.compile("".join(out))


def matching_files(root: Path, pattern: str) -> list[Path]:
    rx = glob_to_regex(pattern)
    found = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and rx.match(path.relative_to(root).as_posix()):
            found.append(path)
    return found


def resolve_arguments(definition: dict, supplied: dict[str, str], sdk_path: str) -> dict[str, str]:
    """Fills in what the caller did not give, in declaration order so a default may use an earlier one.

    `sdkPath` is seeded here and is not in the declared arguments: the template asks for it (to write
    `local.properties`) but only the host knows it.
    """
    values: dict[str, str] = {"sdkPath": sdk_path}
    for argument in definition.get("arguments", []):
        key = argument["id"]
        if key in supplied and supplied[key] != "":
            values[key] = supplied[key]
        else:
            values[key] = expand(str(argument.get("default-value", "")), values)
    for key, value in supplied.items():
        values.setdefault(key, value)
    return values


def apply_transformations(root: Path, definition: dict, values: dict[str, str]) -> None:
    for step in definition.get("transformations", []):
        kind = next(iter(step))
        body = step[kind]
        if kind == "comment":
            continue
        if kind == "string-replace":
            frm = expand(body["from"], values)
            to = expand(body["to"], values)
            for path in matching_files(root, body["selector"]["glob"]):
                text = path.read_text(encoding="utf-8")
                if frm in text:
                    path.write_text(text.replace(frm, to), encoding="utf-8")
            continue
        if kind == "rename-file":
            source = expand(body["source-path"], values)
            target = expand(body["target-path"], values)
            for path in matching_files(root, body["selector"]["glob"]):
                rel = path.relative_to(root).as_posix()
                if source not in rel:
                    continue
                destination = root / rel.replace(source, target)
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(path), str(destination))
            prune_empty_directories(root)
            continue
        raise ValueError(f"the template uses a transformation this does not implement: {kind}")


def prune_empty_directories(root: Path) -> None:
    """A `rename-file` that moves a package leaves its old directories behind, empty."""
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir() and not any(path.iterdir()):
            path.rmdir()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", required=True, help="the extracted template directory")
    parser.add_argument("--project", required=True, help="where the project goes")
    parser.add_argument("--sdk-path", required=True)
    parser.add_argument("--arg", action="append", default=[], metavar="key=value")
    options = parser.parse_args()

    template = Path(options.template)
    project = Path(options.project)
    definition_file = template / ".template" / "template-definition.json"
    if not definition_file.is_file():
        print(f"No template definition at {definition_file}", file=sys.stderr)
        return 1
    definition = json.loads(definition_file.read_text(encoding="utf-8"))

    supplied = {}
    for pair in options.arg:
        key, _, value = pair.partition("=")
        supplied[key] = value

    values = resolve_arguments(definition, supplied, options.sdk_path)
    print(f"== {definition.get('name', 'template')} ==")
    for key in sorted(values):
        print(f"   {key} = {values[key]}")

    project.mkdir(parents=True, exist_ok=True)
    for entry in sorted(template.iterdir()):
        if entry.name == ".template":
            continue
        destination = project / entry.name
        if entry.is_dir():
            shutil.copytree(entry, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(entry, destination)

    apply_transformations(project, definition, values)

    needed = [
        expand(d["sdk-package"], values)
        for d in definition.get("dependencies", [])
        if "sdk-package" in d
    ]
    if needed:
        # Printed rather than installed: the recipe step around this one owns sdkmanager, and a
        # scaffold that quietly downloads a platform is a scaffold that appears to hang.
        print("== SDK packages this project needs ==")
        for package in needed:
            print(f"   {package}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
