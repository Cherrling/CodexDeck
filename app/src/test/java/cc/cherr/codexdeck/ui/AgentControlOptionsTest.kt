package cc.cherr.codexdeck.ui

import cc.cherr.codexdeck.protocol.PermissionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlOptionsTest {
    @Test
    fun deprecatedApprovalValuesAreNotOfferedAsChoices() {
        assertFalse(approvalPolicyOptions.any { it.value == "untrusted" })
        assertFalse(approvalsReviewerOptions.any { it.value == "guardian_subagent" })
        assertTrue(approvalPolicyOptionLabel("untrusted").contains("旧版"))
        assertTrue(approvalsReviewerOptionLabel("guardian_subagent").contains("旧版"))
    }

    @Test
    fun permissionProfilesReplaceLegacySandboxChoices() {
        val picker = permissionPickerOptions(
            profiles = listOf(
                PermissionProfile(":read-only", "", true),
                PermissionProfile(":workspace", "", true),
                PermissionProfile(":danger-full-access", "", true),
                PermissionProfile("managed", "Managed profile", false),
            ),
            selectedProfileId = ":workspace",
            sandboxMode = "danger-full-access",
        )

        assertTrue(picker.profilesSupported)
        assertEquals("权限 · 工作区写入", picker.label)
        assertEquals(
            listOf(":read-only", ":workspace", ":danger-full-access"),
            picker.options.map { it.value },
        )
        assertEquals(
            listOf("只读", "工作区写入", "完全访问（高风险）"),
            picker.options.map { it.label },
        )
    }

    @Test
    fun legacySandboxIsOnlyASingleFallbackForOlderServers() {
        val picker = permissionPickerOptions(
            profiles = emptyList(),
            selectedProfileId = null,
            sandboxMode = "workspace-write",
        )

        assertFalse(picker.profilesSupported)
        assertEquals(
            listOf("read-only", "workspace-write", "danger-full-access"),
            picker.options.map { it.value },
        )
    }

    @Test
    fun managedProfileDenialDoesNotFallBackToLegacySandbox() {
        val picker = permissionPickerOptions(
            profiles = listOf(PermissionProfile(":workspace", "", false)),
            selectedProfileId = ":workspace",
            sandboxMode = "workspace-write",
        )

        assertTrue(picker.profilesSupported)
        assertTrue(picker.options.isEmpty())
        assertEquals("权限 · 受管理限制", picker.label)
    }
}
