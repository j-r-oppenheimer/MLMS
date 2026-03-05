package com.cnumed.mlms.ui.login

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

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(id: String, pwd: String, saveCredentials: Boolean) {
        if (id.isBlank() || pwd.isBlank()) {
            _uiState.value = LoginUiState.Error("아이디와 비밀번호를 입력해주세요")
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "Login attempt for user=$id")
            _uiState.value = LoginUiState.Loading
            val result = sessionManager.login(id, pwd)
            if (result is LoginResult.Success && saveCredentials) {
                securePrefs.saveCredentials(id, pwd)
                securePrefs.setAutoLogin(true)
            }
            _uiState.value = when (result) {
                is LoginResult.Success -> {
                    Log.d(TAG, "Login success")
                    LoginUiState.Success
                }
                is LoginResult.Failure -> {
                    Log.w(TAG, "Login failed: ${result.message}")
                    LoginUiState.Error(result.message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }
}