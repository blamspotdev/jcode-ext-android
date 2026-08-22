#!/bin/sh
# Test
cd $JCODE_PROJECT_DIR || exit 1
if [ -f gradlew ]; then bash gradlew testDebugUnitTest; else gradle testDebugUnitTest; fi
