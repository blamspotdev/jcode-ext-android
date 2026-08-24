#!/bin/sh
# Write MainActivity.kt
set -e

cat > "$JCODE_PROJECT_DIR/app/src/main/java/com/example/$JCODE_PROJECT_NAME/MainActivity.kt" <<EOF
package com.example.$JCODE_PROJECT_NAME

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
EOF
