package com.cnumed.mlms.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnumed.mlms.data.remote.LoginResult
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.util.SecurePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MainLoginState {
    object Checking : MainLoginState()
    object LoggedIn : MainLoginState()
    object NeedCredentials : MainLoginState()
    data class LoginFailed(val message: String) : MainLoginState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _loginState = MutableStateFlow<MainLoginState>(MainLoginState.Checking)
    val loginState: StateFlow<MainLoginState> = _loginState.asStateFlow()

    init {
        attemptBackgroundLogin()
    }

    private fun attemptBackgroundLogin() {
        viewModelScope.launch {
            // 1. 기존 세션이 아직 유효한지 확인
            if (sessionManager.isSessionValid()) {
                Log.d(TAG, "Existing session valid")
                _loginState.value = MainLoginState.LoggedIn
                return@launch
            }

            // 2. 저장된 자격증명이 있으면 로그인 시도
            val id = securePrefs.getId()
            val pwd = securePrefs.getPassword()

            if (id != null && pwd != null) {
                Log.d(TAG, "Session expired, auto re-login for user=$id")
                val result = sessionManager.login(id, pwd)
                _loginState.value = when (result) {
                    is LoginResult.Success -> {
                        Log.d(TAG, "Auto re-login success")
                        MainLoginState.LoggedIn
                    }
                    is LoginResult.Failure -> {
                        Log.w(TAG, "Auto re-login failed: ${result.message}")
                        MainLoginState.LoginFailed(result.message)
                    }
                }
            } else {
                Log.d(TAG, "No saved credentials — need login")
                _loginState.value = MainLoginState.NeedCredentials
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}