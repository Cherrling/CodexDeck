package cc.cherr.codexdeck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.PublicKeyDialogData
import cc.cherr.codexdeck.data.AppServerTransportMode
import cc.cherr.codexdeck.data.HostDraft
import cc.cherr.codexdeck.data.HostRecord
import cc.cherr.codexdeck.data.IdentityGeneration
import cc.cherr.codexdeck.data.IdentityRecord
import cc.cherr.codexdeck.ssh.ConnectionPrompt
import cc.cherr.codexdeck.ssh.SshPrivateKeyInput
import cc.cherr.codexdeck.ssh.TrustDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HostEditorDialog(
    host: HostRecord?,
    hosts: List<HostRecord>,
    identities: List<IdentityRecord>,
    onDismiss: () -> Unit,
    onSave: (HostDraft, String, Boolean, String, Boolean) -> Unit,
) {
    var draft by remember(host?.id) {
        mutableStateOf(host?.let(HostDraft::from) ?: HostDraft(identityId = identities.firstOrNull()?.id))
    }
    var password by remember(host?.id) { mutableStateOf("") }
    var replacePassword by remember(host?.id) { mutableStateOf(host?.encryptedPassword?.isEmpty() != false) }
    var appServerToken by remember(host?.id) { mutableStateOf("") }
    var replaceAppServerToken by remember(host?.id) {
        mutableStateOf(host?.encryptedAppServerToken?.isEmpty() != false)
    }
    var identityMenu by remember { mutableStateOf(false) }
    var jumpMenu by remember { mutableStateOf(false) }
    val showSshSettings =
        draft.appServerTransport in AppServerTransportMode.sshModes || draft.webSocketViaSsh

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (host == null) "添加服务器" else "编辑服务器",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "凭据只保存加密密文；输入密码不等于明文落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = draft.label,
                        onValueChange = { draft = draft.copy(label = it) },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.groupName,
                            onValueChange = { draft = draft.copy(groupName = it) },
                            label = { Text("分组") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.tags,
                            onValueChange = { draft = draft.copy(tags = it) },
                            label = { Text("标签，逗号分隔") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    if (showSshSettings) {
                        OutlinedTextField(
                            value = draft.hostname,
                            onValueChange = { draft = draft.copy(hostname = it) },
                            label = { Text("主机名 / IP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.port,
                                onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit)) },
                                label = { Text("SSH 端口") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = draft.username,
                                onValueChange = { draft = draft.copy(username = it) },
                                label = { Text("用户名") },
                                singleLine = true,
                                modifier = Modifier.weight(1.5f),
                            )
                        }
                        Text("认证", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.authType == "key",
                                onClick = { draft = draft.copy(authType = "key") },
                                label = { Text("SSH Key") },
                            )
                            FilterChip(
                                selected = draft.authType == "password",
                                onClick = { draft = draft.copy(authType = "password") },
                                label = { Text("密码") },
                            )
                        }
                        if (draft.authType == "key") {
                            Box {
                                TextButton(onClick = { identityMenu = true }) {
                                    Text(
                                        identities.firstOrNull { it.id == draft.identityId }?.label
                                            ?: "选择 SSH 身份",
                                    )
                                }
                                DropdownMenu(identityMenu, onDismissRequest = { identityMenu = false }) {
                                    identities.forEach { identity ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(identity.label)
                                                    Text(
                                                        identity.algorithm,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                draft = draft.copy(identityId = identity.id)
                                                identityMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            if (host?.encryptedPassword?.isNotEmpty() == true) {
                                Row {
                                    Checkbox(
                                        checked = replacePassword,
                                        onCheckedChange = { replacePassword = it },
                                    )
                                    Text("替换已保存密码", Modifier.padding(top = 12.dp))
                                }
                            }
                            if (replacePassword || host?.encryptedPassword?.isEmpty() != false) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("登录密码（仅加密保存）") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Text("Codex App Server", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected =
                                draft.appServerTransport == AppServerTransportMode.SSH_EXISTING,
                            onClick = {
                                draft = draft.copy(
                                    appServerTransport = AppServerTransportMode.SSH_EXISTING,
                                )
                            },
                            label = { Text("已有 App Server") },
                        )
                        FilterChip(
                            selected = draft.appServerTransport == AppServerTransportMode.SSH_LAUNCH,
                            onClick = {
                                draft = draft.copy(
                                    appServerTransport = AppServerTransportMode.SSH_LAUNCH,
                                )
                            },
                            label = { Text("启动 stdio") },
                        )
                    }
                    FilterChip(
                        selected = draft.appServerTransport == AppServerTransportMode.WEBSOCKET,
                        onClick = {
                            draft = draft.copy(
                                appServerTransport = AppServerTransportMode.WEBSOCKET,
                            )
                        },
                        label = { Text("WebSocket / SSH Tunnel") },
                    )
                    if (draft.appServerTransport == AppServerTransportMode.SSH_EXISTING) {
                        Text(
                            "通过 codex app-server proxy 连接当前用户已运行的 daemon；" +
                                "不会启动第二个 App Server。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (draft.appServerTransport == AppServerTransportMode.WEBSOCKET) {
                        OutlinedTextField(
                            value = draft.appServerUrl,
                            onValueChange = { draft = draft.copy(appServerUrl = it) },
                            label = { Text("WebSocket URL") },
                            supportingText = {
                                Text("远端监听地址，例如 ws://127.0.0.1:8765 或 wss://codex.example.com")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = draft.webSocketViaSsh,
                                onCheckedChange = {
                                    draft = draft.copy(
                                        webSocketViaSsh = it,
                                        jumpHostId = if (it) draft.jumpHostId else null,
                                    )
                                },
                            )
                            Text("通过此 Host 的 SSH 链路建立本地端口转发")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = draft.allowInsecureWebSocket,
                                onCheckedChange = {
                                    draft = draft.copy(allowInsecureWebSocket = it)
                                },
                            )
                            Text("允许非回环明文 ws://（不推荐）")
                        }
                        if (host?.encryptedAppServerToken?.isNotEmpty() == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = replaceAppServerToken,
                                    onCheckedChange = { replaceAppServerToken = it },
                                )
                                Text("替换已保存的 Bearer Token")
                            }
                        }
                        if (replaceAppServerToken ||
                            host?.encryptedAppServerToken?.isEmpty() != false
                        ) {
                            OutlinedTextField(
                                value = appServerToken,
                                onValueChange = { appServerToken = it },
                                label = { Text("Bearer Token（可留空）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (showSshSettings) {
                        Text("连接路径", style = MaterialTheme.typography.titleSmall)
                        Box {
                            TextButton(onClick = { jumpMenu = true }) {
                                Text(
                                    "跳板机：" +
                                        (hosts.firstOrNull { it.id == draft.jumpHostId }?.label ?: "直接连接"),
                                )
                            }
                            DropdownMenu(jumpMenu, onDismissRequest = { jumpMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("直接连接") },
                                    onClick = {
                                        draft = draft.copy(jumpHostId = null)
                                        jumpMenu = false
                                    },
                                )
                                hosts.filter { it.id != host?.id }.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            draft = draft.copy(jumpHostId = option.id)
                                            jumpMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            "多级跳板机沿 Host 的 jumpHostId 链展开，每一跳独立校验 Host Key 和认证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (draft.appServerTransport in AppServerTransportMode.sshModes) {
                        OutlinedTextField(
                            value = draft.codexExecutable,
                            onValueChange = { draft = draft.copy(codexExecutable = it) },
                            label = { Text("远端 Codex 可执行文件") },
                            supportingText = {
                                Text(
                                    if (draft.appServerTransport ==
                                        AppServerTransportMode.SSH_EXISTING
                                    ) {
                                        "执行 codex app-server proxy；例如 codex 或 /opt/codex/bin/codex"
                                    } else {
                                        "执行 codex app-server --listen stdio://；创建独立进程"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.connectTimeoutSeconds,
                            onValueChange = {
                                draft = draft.copy(connectTimeoutSeconds = it.filter(Char::isDigit))
                            },
                            label = { Text("超时秒") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.keepAliveSeconds,
                            onValueChange = {
                                draft = draft.copy(keepAliveSeconds = it.filter(Char::isDigit))
                            },
                            label = { Text("Keepalive") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.retryCount,
                            onValueChange = {
                                draft = draft.copy(retryCount = it.filter(Char::isDigit))
                            },
                            label = { Text("重试") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = {
                        onSave(
                            draft,
                            password,
                            replacePassword || host?.encryptedPassword?.isEmpty() != false,
                            appServerToken,
                            replaceAppServerToken ||
                                host?.encryptedAppServerToken?.isEmpty() != false,
                        )
                    }) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun ImportIdentityDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (String, ByteArray, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val interactionBusy = busy || loading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected == null) return@rememberLauncherForActivityResult
        uri = selected
        pasted = ""
        errorMessage = null
    }
    AlertDialog(
        onDismissRequest = { if (!interactionBusy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("导入 SSH 私钥") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("身份名称") },
                    singleLine = true,
                    enabled = !interactionBusy,
                )
                Text(
                    "支持 OpenSSH 与 PEM / PKCS#8 私钥（Ed25519、RSA、ECDSA）；私钥加密保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !interactionBusy,
                ) {
                    Text(
                        if (uri == null) {
                            "选择私钥文件"
                        } else {
                            "已选择：${uri?.lastPathSegment ?: "私钥文件"}"
                        },
                    )
                }
                if (uri == null) {
                    OutlinedTextField(
                        pasted,
                        {
                            if (it.length <= SshPrivateKeyInput.MAX_BYTES) pasted = it
                        },
                        label = { Text("或粘贴私钥") },
                        minLines = 3,
                        maxLines = 5,
                        enabled = !interactionBusy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    TextButton(
                        onClick = {
                            uri = null
                            errorMessage = null
                        },
                        enabled = !interactionBusy,
                    ) {
                        Text("改用粘贴")
                    }
                }
                OutlinedTextField(
                    passphrase,
                    { passphrase = it },
                    label = { Text("原私钥口令（如未加密可留空）") },
                    singleLine = true,
                    enabled = !interactionBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (interactionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !interactionBusy && (uri != null || pasted.isNotBlank()),
                onClick = {
                    val selectedUri = uri
                    if (selectedUri == null && pasted.isBlank()) {
                        errorMessage = "请选择私钥文件或粘贴私钥"
                        return@TextButton
                    }
                    loading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val pem = if (selectedUri == null) {
                                SshPrivateKeyInput.fromPaste(pasted)
                            } else {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(selectedUri)?.use {
                                        SshPrivateKeyInput.readFromStream(it)
                                    } ?: error("无法读取所选文件")
                                }
                            }
                            onImport(label, pem, passphrase)
                        } catch (failure: Throwable) {
                            errorMessage = failure.message ?: "读取私钥失败"
                        } finally {
                            loading = false
                        }
                    }
                },
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !interactionBusy) { Text("取消") }
        },
    )
}

@Composable
fun GenerateIdentityDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, IdentityGeneration, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(IdentityGeneration.ED25519) }
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成 SSH 身份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("身份名称") }, singleLine = true)
                IdentityGeneration.entries.forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = { type = option },
                        label = { Text(option.name) },
                    )
                }
                OutlinedTextField(
                    passphrase,
                    { passphrase = it },
                    label = { Text("私钥口令（可选，建议设置）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("口令不保存；仅私钥本体由 Android Keystore 加密保存") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onGenerate(label, type, passphrase) }) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PublicKeyDialog(
    data: PublicKeyDialogData,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportPublicKey = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(data.publicKey)
                    writer.newLine()
                }
            }
        }
    }
    val exportName = (data.label.filter { it.isLetterOrDigit() || it in "-_." }
        .ifBlank { "id" }
        .take(64)) + ".pub"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${data.label} 公钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(data.algorithm, style = MaterialTheme.typography.labelLarge)
                Text(data.fingerprint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = data.publicKey,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { exportPublicKey.launch(exportName) }) {
                    Text("导出公钥")
                }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SSH public key", data.publicKey))
                    onDismiss()
                }) { Text("复制并关闭") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ConnectionPromptDialogs(
    controller: AppController,
    state: AppState,
) {
    when (val prompt = state.pendingPrompt) {
        is ConnectionPrompt.Secret -> {
            var secret by remember(prompt.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { controller.submitPromptSecret(prompt, "") },
                properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
                title = { Text(if (prompt.isPassword) "SSH 登录密码" else "解锁 SSH 私钥") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(prompt.detail)
                        Text(
                            "凭据仅用于本次连接，不会写入日志。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(if (prompt.isPassword) "密码" else "Passphrase") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = secret
                        secret = ""
                        controller.submitPromptSecret(prompt, value)
                    }) { Text("继续连接") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        secret = ""
                        controller.submitPromptSecret(prompt, "")
                    }) { Text("取消") }
                },
            )
        }
        is ConnectionPrompt.HostKey -> {
            val challenge = prompt.challenge
            AlertDialog(
                onDismissRequest = { controller.submitHostTrust(prompt, TrustDecision.CANCEL) },
                title = {
                    Text(if (challenge.previous == null) "确认服务器指纹" else "警告：服务器指纹已改变")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${challenge.hostname}:${challenge.port}")
                        if (challenge.previous != null) {
                            Text(
                                "这可能表示服务器重装、密钥轮换，或连接被冒充。请通过可信渠道核对。",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text("已保存：${challenge.previous.fingerprint}")
                        }
                        Text("本次：${challenge.presented.algorithm}")
                        MonoText(challenge.presented.fingerprint)
                    }
                },
                confirmButton = {
                    Column {
                        TextButton(onClick = {
                            controller.submitHostTrust(prompt, TrustDecision.SAVE)
                        }) {
                            Text(if (challenge.previous == null) "信任并保存" else "确认更换并保存")
                        }
                        TextButton(onClick = {
                            controller.submitHostTrust(prompt, TrustDecision.ONCE)
                        }) { Text("仅信任本次") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        controller.submitHostTrust(prompt, TrustDecision.CANCEL)
                    }) { Text("取消") }
                },
            )
        }
        null -> Unit
    }
}
