package io.onexr.demo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.onexr.demo.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonSensorDemo.setOnClickListener {
            startActivity(Intent(this, SensorDemoActivity::class.java))
        }
        binding.buttonControlsDemo.setOnClickListener {
            startActivity(Intent(this, ControlsDemoActivity::class.java))
        }
    }
}
