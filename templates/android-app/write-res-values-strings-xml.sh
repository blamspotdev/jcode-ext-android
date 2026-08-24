#!/bin/sh
# Write res/values/strings.xml
set -e

cat > "$JCODE_PROJECT_DIR/app/src/main/res/values/strings.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$JCODE_PROJECT_NAME</string>
</resources>
EOF
