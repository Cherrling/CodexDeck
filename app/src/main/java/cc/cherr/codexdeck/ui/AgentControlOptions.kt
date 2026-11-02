package cc.cherr.codexdeck.ui

import cc.cherr.codexdeck.protocol.PermissionProfile
import cc.cherr.codexdeck.sandboxModeLabel

internal data class ControlOption(
    val value: String,
    val label: String,
)

internal val approvalPolicyOptions = listOf(
    ControlOption("on-request", "按需审批"),
    ControlOption("never", "无需审批"),
)

internal val approvalsReviewerOptions = listOf(
    ControlOption("user", "用户审批"),
    ControlOption("auto_review", "自动审查"),
)

internal fun approvalPolicyOptionLabel(value: String): String = when (value) {
    "never" -> "无需审批"
    "granular" -> "细粒度审批"
    "untrusted" -> "旧版仅信任（不可选）"
    else -> "按需审批"
}

internal fun approvalsReviewerOptionLabel(value: String): String = when (value) {
    "auto_review" -> "自动审查"
    "guardian_subagent" -> "自动审查（旧版服务器值）"
    else -> "用户审批"
}

internal data class PermissionPickerOptions(
    val label: String,
    val options: List<ControlOption>,
    val profilesSupported: Boolean,
)

internal fun permissionPickerOptions(
    profiles: List<PermissionProfile>,
    selectedProfileId: String?,
    sandboxMode: String,
): PermissionPickerOptions {
    if (profiles.isEmpty()) {
        return PermissionPickerOptions(
            label = "权限 · ${sandboxModeLabel(sandboxMode)}",
            options = listOf(
                ControlOption("read-only", "只读"),
                ControlOption("workspace-write", "工作区写入"),
                ControlOption("danger-full-access", "完全访问（高风险）"),
            ),
            profilesSupported = false,
        )
    }

    val allowedProfiles = profiles.filter(PermissionProfile::allowed)
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val currentLabel = when {
        selectedProfile != null -> permissionProfileLabel(selectedProfile)
        !selectedProfileId.isNullOrBlank() -> selectedProfileId
        else -> "${sandboxModeLabel(sandboxMode)}（当前会话）"
    }
    return PermissionPickerOptions(
        label = if (allowedProfiles.isEmpty()) {
            "权限 · 受管理限制"
        } else {
            "权限 · $currentLabel"
        },
        options = allowedProfiles.map { profile ->
            ControlOption(profile.id, permissionProfileLabel(profile))
        },
        profilesSupported = true,
    )
}

private fun permissionProfileLabel(profile: PermissionProfile): String =
    when (profile.id) {
        ":read-only" -> "只读"
        ":workspace" -> "工作区写入"
        ":danger-full-access" -> "完全访问（高风险）"
        else -> profile.description.ifBlank { profile.id }
    }
