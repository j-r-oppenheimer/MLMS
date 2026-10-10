package com.cnumed.mlms.ui.main

import com.cnumed.mlms.util.AppLog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnumed.mlms.data.remote.LoginResult
import com.cnumed.mlms.data.remote.SessionManager
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
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _loginState = MutableStateFlow<MainLoginState>(MainLoginState.Checking)
    val loginState: StateFlow<MainLoginState> = _loginState.asStateFlow()

    init {
        attemptBackgroundLogin()
    }

    private fun attemptBackgroundLogin() {
        viewModelScope.launch {
            _loginState.value = when (val result = sessionManager.restoreSession()) {
                null -> {
                    AppLog.d(TAG, "No saved credentials — need login")
                    MainLoginState.NeedCredentials
                }
                is LoginResult.Success -> MainLoginState.LoggedIn
                is LoginResult.Failure -> {
                    AppLog.w(TAG, "Auto re-login failed: ${result.message}")
                    MainLoginState.LoginFailed(result.message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}