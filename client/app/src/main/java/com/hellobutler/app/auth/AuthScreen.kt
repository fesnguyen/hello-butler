package com.hellobutler.app.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.hellobutler.app.BuildConfig
import com.hellobutler.app.ui.theme.ButlerForest
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val state by viewModel.state.collectAsState()
    AuthContent(
        state = state,
        onEmailSubmit = { email, password, register ->
            if (register) viewModel.register(email, password) else viewModel.login(email, password)
        },
        onGoogleToken = viewModel::google,
        onError = viewModel::showError,
    )
}

@Composable
private fun AuthContent(
    state: AuthUiState,
    onEmailSubmit: (String, String, Boolean) -> Unit,
    onGoogleToken: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    val emailError = attempted && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordError = attempted && password.length < 8

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.size(260.dp).offset(x = 210.dp, y = (-95).dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f), CircleShape)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(34.dp))
            ButlerMark()
            Spacer(Modifier.height(20.dp))
            Text("Hello, I’m Butler.", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (registering) "Let’s prepare a calmer day together."
                else "Your day, prepared and quietly kept on track.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (registering) "Create your account" else "Welcome back", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (registering) "Start with the essentials. Butler can learn the rest naturally."
                        else "Continue to today’s plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GoogleButton(enabled = !state.loading) {
                        if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isBlank()) {
                            onError("Google sign-in is not configured for this build")
                            return@GoogleButton
                        }
                        scope.launch {
                            runCatching {
                                val option = GetGoogleIdOption.Builder()
                                    .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                                    .setFilterByAuthorizedAccounts(false).build()
                                val credential = credentialManager.getCredential(
                                    context, GetCredentialRequest.Builder().addCredentialOption(option).build()
                                ).credential
                                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                                GoogleIdTokenCredential.createFrom(credential.data).idToken
                            }.onSuccess(onGoogleToken)
                                .onFailure { onError(it.message ?: "Google sign-in failed") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text("or use email", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email address") }, leadingIcon = { Icon(Icons.Outlined.Email, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
                        isError = emailError, supportingText = { if (emailError) Text("Enter a valid email address") },
                        shape = RoundedCornerShape(16.dp), enabled = !state.loading,
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { revealPassword = !revealPassword }) {
                                Icon(
                                    if (revealPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (revealPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                        visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = passwordError, supportingText = { if (passwordError) Text("Use at least 8 characters") },
                        shape = RoundedCornerShape(16.dp), enabled = !state.loading,
                    )
                    state.error?.let { ErrorBanner(it) }
                    Button(
                        onClick = {
                            attempted = true
                            val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
                            if (validEmail && password.length >= 8) onEmailSubmit(email.trim(), password, registering)
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !state.loading,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.loading) CircularProgressIndicator(
                            Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp
                        ) else Text(if (registering) "Create account" else "Sign in")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { registering = !registering; attempted = false }, enabled = !state.loading) {
                Text(if (registering) "Already have an account?  Sign in" else "New to Butler?  Create an account")
            }
        }
    }
}

@Composable
private fun ButlerMark() {
    Surface(shape = RoundedCornerShape(22.dp), color = ButlerForest, shadowElevation = 8.dp) {
        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
            Text("B", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        }
    }
}

@Composable
private fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
        Surface(Modifier.size(24.dp), shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(contentAlignment = Alignment.Center) { Text("G", color = Color(0xFF4285F4), style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.width(10.dp))
        Text("Continue with Google")
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}
