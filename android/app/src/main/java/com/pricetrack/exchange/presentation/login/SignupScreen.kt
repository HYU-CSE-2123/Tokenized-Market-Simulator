package com.pricetrack.exchange.presentation.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 회원가입 화면 (기획서 §14.2). TODO: AuthViewModel 연동. */
@Composable
fun SignupScreen(onSignupSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("회원가입")
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("이메일") })
        OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("닉네임") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("비밀번호") })
        Button(onClick = onSignupSuccess) { Text("가입하기") }
    }
}
