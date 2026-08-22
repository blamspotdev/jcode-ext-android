#!/bin/sh
# Clean
cd $JCODE_PROJECT_DIR || exit 1
if [ -f gradlew ]; then bash gradlew clean; else gradle clean; fi
