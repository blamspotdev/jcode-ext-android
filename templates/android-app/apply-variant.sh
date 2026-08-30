#!/bin/sh
# Turn the SDK's scaffold into the chosen variant
set -e

VARIANT="${JCODE_INPUT_VARIANT:-empty-compose}"

# The SDK's own template *is* the Compose one, so that variant is what the previous step already
# produced and there is nothing to do. Every other entry in the gallery is this pack's, applied on
# top of the harness the SDK gives us -- the wrapper, the launcher icons, the versions catalogue and
# a Gradle setup that is known to build on this device. Authoring those again per variant would be
# authoring the one part that is hard to get right and easy to get wrong.
if [ "$VARIANT" = "empty-compose" ]; then
  exit 0
fi

TEMPLATE_DIR="$(dirname "$0")"
SRC="$TEMPLATE_DIR/variants/$VARIANT"
[ -d "$SRC" ] || {
  echo "This pack has no variant named \"$VARIANT\"."
  exit 1
}

echo "== Applying the $VARIANT variant =="
python3 "$TEMPLATE_DIR/apply-variant.py" --project "$JCODE_PROJECT_DIR" --variant "$SRC"
