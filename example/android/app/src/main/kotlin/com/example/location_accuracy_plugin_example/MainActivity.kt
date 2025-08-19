package com.example.location_accuracy_plugin_example

import com.example.location_accuracy_plugin.LocationAccuracyPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugins.GeneratedPluginRegistrant

class MainActivity : FlutterActivity(){
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Manually add the plugin (not needed if auto-registration works)
        flutterEngine.plugins.add(LocationAccuracyPlugin())
    }
}
