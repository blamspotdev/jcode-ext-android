#!/bin/sh
# Build
cd $JCODE_PROJECT_DIR || exit 1
if [ -f gradlew ]; then bash gradlew bundleRelease; else gradle bundleRelease; fi
