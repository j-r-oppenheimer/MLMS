package com.cnumed.mlms.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.cnumed.mlms.R
import com.cnumed.mlms.databinding.ActivityMainBinding
import com.cnumed.mlms.ui.login.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 허용/거부 여부와 무관하게 앱은 정상 동작 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        requestNotificationPermission()
        observeLoginState()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            viewModel.loginState.collect { state ->
                when (state) {
                    is MainLoginState.NeedCredentials -> {
                        // 자격 증명이 없을 때만 로그인 화면으로 이동
                        navigateToLogin()
                    }
                    is MainLoginState.LoginFailed -> {
                        // 로그인 실패 시 토스트만 표시, 캐시된 데이터는 계속 사용 가능
                        Toast.makeText(
                            this@MainActivity,
                            "로그인 실패: ${state.message}\n캐시된 데이터를 표시합니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is MainLoginState.LoggedIn -> {
                        // 로그인 성공 - 아무것도 하지 않음 (이미 메인 화면에 있음)
                    }
                    is MainLoginState.Checking -> {
                        // 로그인 확인 중 - 아무것도 하지 않음
                    }
                }
            }
        }
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}