#!/bin/sh
# Install
cd $JCODE_PROJECT_DIR || exit 1
if [ -f gradlew ]; then bash gradlew installDebug; else gradle installDebug; fi
