package com.codebox.installer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Codebox Installer ready"
        tv.textSize = 20f
        tv.setPadding(40, 80, 40, 40)
        setContentView(tv)
    }
}
