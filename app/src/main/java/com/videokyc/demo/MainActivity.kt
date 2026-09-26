package com.videokyc.demo

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.videokyc.demo.databinding.ActivityMainBinding
import com.videokyc.sdk.KycSdkContract
import com.videokyc.sdk.KycSdkRequest
import com.videokyc.sdk.KycSdkResult

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val kycLauncher = registerForActivityResult(KycSdkContract()) { result: KycSdkResult? ->
        binding.result.text = result?.let {
            "Result: ${it.resultCode}\nStatus: ${it.status ?: "-"}\nSession: ${it.sessionCode ?: it.sessionId ?: "-"}\n${it.message ?: ""}"
        } ?: "KYC closed without a result"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.start.setOnClickListener {
           /* kycLauncher.launch(

            )*/
        }
    }
}
