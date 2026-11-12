package com.wangzan101.mika.core.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTargetPolicyTest {
    private val policy = AutomationTargetPolicy(
        packageName = "com.haoyisheng.mobile.app",
        protectedText = setOf("考试", "答题", "人脸", "身份验证"),
        protectedScreenText = setOf("考试", "答题", "学习声明", "确认申领"),
        allowedClickResourceIds = setOf(
            "com.haoyisheng.mobile.app:id/iv_play_pause",
            "com.haoyisheng.mobile.app:id/tv_score_apply",
            "com.haoyisheng.mobile.app:id/recommend_course_name",
        ),
        allowedClickText = setOf("课程列表"),
        allowedRowText = setOf("学习中", "未学习"),
    )

    @Test fun onlyTheConfiguredPackageCanBeLaunchedOrOperated() {
        assertTrue(policy.allowsPackage("com.haoyisheng.mobile.app"))
        assertFalse(policy.allowsPackage("com.android.settings"))
        assertFalse(policy.allowsPackage(null))
    }

    @Test fun creditExamAndIdentityActionsAreProtected() {
        assertFalse(policy.isProtected("com.haoyisheng.mobile.app:id/tv_score_apply", "申请学分", null))
        assertTrue(policy.isProtected(null, "进入考试", null))
        assertTrue(policy.isProtected(null, null, "开始人脸识别"))
        assertFalse(policy.isProtected("com.haoyisheng.mobile.app:id/iv_play_pause", "继续学习", null))
        assertTrue(policy.isProtectedScreen("请阅读学习声明", null))
        assertTrue(policy.isProtectedScreen(null, "确认申领学分"))
        assertFalse("课程详情上的申请入口不应阻止安全的视频导航", policy.isProtectedScreen("申请学分", null))
    }

    @Test fun onlyKnownPlaybackActionsCanBeClicked() {
        assertTrue(policy.allowsResourceClick("com.haoyisheng.mobile.app:id/iv_play_pause"))
        assertTrue(policy.allowsTextClick("课程列表"))
        assertTrue(policy.allowsRowClick("未学习"))
        assertTrue(policy.allowsResourceClick("com.haoyisheng.mobile.app:id/tv_score_apply"))
        assertTrue(policy.allowsResourceClick("com.haoyisheng.mobile.app:id/recommend_course_name"))
        assertFalse(policy.allowsTextClick("继续学习"))
        assertFalse(policy.allowsRowClick("申请学分"))
    }
}
