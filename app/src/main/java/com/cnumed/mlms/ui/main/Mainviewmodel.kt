package com.cnumed.mlms.ui.main

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
                _loginState.value = MainLoginState.LoggedIn
                return@launch
            }

            // 2. 저장된 자격증명이 있으면 로그인 시도
            val id = securePrefs.getId()
            val pwd = securePrefs.getPassword()

            if (id != null && pwd != null) {
                val result = sessionManager.login(id, pwd)
                _loginState.value = when (result) {
                    is LoginResult.Success -> MainLoginState.LoggedIn
                    is LoginResult.Failure -> MainLoginState.LoginFailed(result.message)
                }
            } else {
                // 자격증명이 없으면 로그인 화면으로 이동 필요
                _loginState.value = MainLoginState.NeedCredentials
            }
        }
    }
}