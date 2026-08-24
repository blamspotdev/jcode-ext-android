#!/bin/sh
# Lint
cd $JCODE_PROJECT_DIR || exit 1
if [ -f gradlew ]; then bash gradlew lintDebug; else gradle lintDebug; fi
