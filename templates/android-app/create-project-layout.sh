#!/bin/sh
# Create project layout
set -e

mkdir -p "$JCODE_PROJECT_DIR/app/src/main/java/com/example/$JCODE_PROJECT_NAME"
mkdir -p "$JCODE_PROJECT_DIR/app/src/main/res/layout"
mkdir -p "$JCODE_PROJECT_DIR/app/src/main/res/values"
mkdir -p "$JCODE_PROJECT_DIR/.jcode"
