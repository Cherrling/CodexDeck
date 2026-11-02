package cc.cherr.codexdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState

@Composable
fun IdentitiesScreen(
    state: AppState,
    controller: AppController,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onGenerate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            SectionHeading(
                title = "SSH 身份与密钥",
                subtitle = "多个 Host 可复用同一身份；私钥仅保存 Keystore 加密密文",
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onImport) { Text("导入私钥") }
            OutlinedButton(onClick = onGenerate) { Text("生成密钥") }
        }
        if (state.identities.isEmpty()) {
            EmptyPanel(
                title = "还没有 SSH 身份",
                detail = "支持 OpenSSH、PEM、PKCS#8；生成支持 Ed25519、RSA 3072 和 ECDSA P-256。",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.identities, key = { it.id }) { identity ->
                    var menu by remember(identity.id) { mutableStateOf(false) }
                    val users = state.hosts.count { it.identityId == identity.id }
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(identity.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${identity.algorithm} · ${users} 个 Host 引用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MonoText(identity.fingerprint)
                            }
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "身份操作")
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("查看公钥") },
                                        onClick = {
                                            menu = false
                                            controller.showPublicKey(identity.id)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除身份") },
                                        enabled = users == 0,
                                        onClick = {
                                            menu = false
                                            onDelete(identity.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
