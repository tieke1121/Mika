package com.wangzan101.mika.core.automation

/** 将脚本能力限制到一个目标 App，并保护必须由用户完成的操作。 */
data class AutomationTargetPolicy(
    val packageName: String,
    val protectedResourceIds: Set<String> = emptySet(),
    val protectedText: Set<String> = emptySet(),
    val protectedScreenText: Set<String> = emptySet(),
    val allowedClickResourceIds: Set<String> = emptySet(),
    val allowedClickText: Set<String> = emptySet(),
    val allowedRowText: Set<String> = emptySet(),
) {
    fun allowsPackage(candidate: String?): Boolean = candidate == packageName
    fun allowsResourceClick(resourceId: String): Boolean = resourceId in allowedClickResourceIds
    fun allowsTextClick(text: String): Boolean = text in allowedClickText
    fun allowsRowClick(text: String): Boolean = text in allowedRowText

    fun isProtected(resourceId: String?, text: String?, description: String?): Boolean =
        resourceId in protectedResourceIds || protectedText.any { word ->
            text?.contains(word, ignoreCase = true) == true || description?.contains(word, ignoreCase = true) == true
        }

    fun isProtectedScreen(text: String?, description: String?): Boolean = protectedScreenText.any { word ->
        text?.contains(word, ignoreCase = true) == true || description?.contains(word, ignoreCase = true) == true
    }
}
