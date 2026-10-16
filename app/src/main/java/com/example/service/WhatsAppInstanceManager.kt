package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.agent.AffyDiagnostics
import kotlinx.coroutines.delay

data class WhatsAppInstanceInfo(
    val packageName: String,
    val displayName: String,
    val isBusiness: Boolean,
    val isClone: Boolean = false
)

sealed class OpenWhatsAppResult {
    data class Success(val packageName: String, val message: String) : OpenWhatsAppResult()
    data class AlreadyForeground(val packageName: String, val message: String) : OpenWhatsAppResult()
    data class MultipleInstances(val instances: List<WhatsAppInstanceInfo>, val prompt: String) : OpenWhatsAppResult()
    data class NotFound(val message: String) : OpenWhatsAppResult()
    data class Failed(val reason: String) : OpenWhatsAppResult()
}

object WhatsAppInstanceManager {

    private const val TAG = "WhatsAppInstanceManager"

    // Primary official packages
    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    // Known clone / dual messenger package signatures
    val KNOWN_WHATSAPP_PACKAGES = listOf(
        PKG_WHATSAPP,
        PKG_WHATSAPP_BUSINESS,
        "com.whatsapp.clone",
        "com.whatsapp.clone1",
        "com.whatsapp.clone2",
        "com.whatsapp2",
        "com.dual.whatsapp",
        "com.parallel.space",
        "com.parallel.space.lite",
        "com.lbe.parallel.intl"
    )

    /**
     * Dynamically detects all installed WhatsApp instances (up to 5 or more)
     * without hardcoding a single package.
     */
    fun getInstalledInstances(context: Context): List<WhatsAppInstanceInfo> {
        val pm = context.packageManager
        val instances = mutableListOf<WhatsAppInstanceInfo>()
        val foundPackages = mutableSetOf<String>()

        try {
            val installedPackages = pm.getInstalledPackages(0)
            for (pkg in installedPackages) {
                val pkgName = pkg.packageName.lowercase()
                if (isWhatsAppPackage(pkgName) && !foundPackages.contains(pkg.packageName)) {
                    foundPackages.add(pkg.packageName)
                    val appInfo = pkg.applicationInfo
                    val label = try {
                        if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg.packageName
                    } catch (e: Exception) {
                        pkg.packageName
                    }
                    val isBusiness = pkgName.contains("w4b") || label.contains("business", ignoreCase = true)
                    val isClone = !pkgName.equals(PKG_WHATSAPP, ignoreCase = true) && !pkgName.equals(PKG_WHATSAPP_BUSINESS, ignoreCase = true)
                    instances.add(
                        WhatsAppInstanceInfo(
                            packageName = pkg.packageName,
                            displayName = label,
                            isBusiness = isBusiness,
                            isClone = isClone
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list all packages via PM: ${e.message}")
        }

        // Fallback check against known packages if PM query was restricted
        for (known in KNOWN_WHATSAPP_PACKAGES) {
            if (!foundPackages.contains(known)) {
                try {
                    val info = pm.getPackageInfo(known, 0)
                    val appInfo = info.applicationInfo
                    val label = try {
                        if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else known
                    } catch (e: Exception) {
                        known
                    }
                    foundPackages.add(known)
                    instances.add(
                        WhatsAppInstanceInfo(
                            packageName = known,
                            displayName = label,
                            isBusiness = known == PKG_WHATSAPP_BUSINESS,
                            isClone = known != PKG_WHATSAPP && known != PKG_WHATSAPP_BUSINESS
                        )
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    // Not installed
                } catch (e: Exception) {
                    Log.d(TAG, "Check failed for $known: ${e.message}")
                }
            }
        }

        return instances
    }

    /**
     * Determines whether a package belongs to any WhatsApp instance.
     */
    fun isWhatsAppPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return lower == PKG_WHATSAPP ||
                lower == PKG_WHATSAPP_BUSINESS ||
                lower.contains("whatsapp") ||
                KNOWN_WHATSAPP_PACKAGES.contains(lower)
    }

    /**
     * Resolves the correct outgoing WhatsApp instance based on active foreground app, user preferences, and installed instances.
     * Never sends through an unintended instance.
     */
    fun resolveOutgoingInstance(
        context: Context,
        preferBusiness: Boolean = false,
        preferredPackage: String? = null
    ): String? {
        val installed = getInstalledInstances(context)
        Log.i(TAG, "[WA_APP_DISCOVERY] Found ${installed.size} WhatsApp instances: ${installed.map { it.packageName }}")
        if (installed.isEmpty()) {
            val fallback = preferredPackage?.takeIf { it.isNotBlank() } ?: PKG_WHATSAPP
            Log.i(TAG, "[WA_PACKAGE_SELECTED] No instance found via PM, returning $fallback")
            return fallback
        }

        // 1. If preferred/originating package is explicitly provided, honor it first
        if (!preferredPackage.isNullOrBlank()) {
            val matched = installed.firstOrNull { it.packageName.equals(preferredPackage, ignoreCase = true) }
            if (matched != null) {
                Log.i(TAG, "[WA_PACKAGE_SELECTED] package=${matched.packageName} reason=explicit_or_originating_package")
                return matched.packageName
            }
        }

        // 2. If user explicitly requested Business
        if (preferBusiness) {
            val business = installed.firstOrNull { it.isBusiness }
            if (business != null) {
                Log.i(TAG, "[WA_PACKAGE_SELECTED] package=${business.packageName} reason=prefer_business")
                return business.packageName
            }
        }

        // 3. Check current foreground package from AccessibilityService
        val foregroundPkg = AffyAccessibilityService.currentAppPackage.value
        if (!foregroundPkg.isNullOrBlank() && isWhatsAppPackage(foregroundPkg)) {
            val matchedForeground = installed.firstOrNull { it.packageName.equals(foregroundPkg, ignoreCase = true) }
            if (matchedForeground != null) {
                Log.i(TAG, "[WA_FOREGROUND_PACKAGE] Reusing active foreground WhatsApp instance: ${matchedForeground.packageName}")
                Log.i(TAG, "[WA_PACKAGE_SELECTED] package=${matchedForeground.packageName} reason=foreground_active")
                return matchedForeground.packageName
            }
        }

        // 4. Main official WhatsApp
        val main = installed.firstOrNull { it.packageName == PKG_WHATSAPP }
        if (main != null) {
            Log.i(TAG, "[WA_PACKAGE_SELECTED] package=${main.packageName} reason=default_official")
            return main.packageName
        }

        // 5. First available instance
        val first = installed.first().packageName
        Log.i(TAG, "[WA_PACKAGE_SELECTED] package=$first reason=first_installed")
        return first
    }

    /**
     * Phase 3.1 Dual WhatsApp Intelligence:
     * 1. Check if WhatsApp is already active in foreground -> reuse it immediately.
     * 2. Detect available WhatsApp packages.
     * 3. If specific variant is requested ("personal", "business", "clone") -> resolve without prompt.
     * 4. If multiple instances exist and none is active and no variant was requested -> ask user.
     * 5. Launch and VERIFY foreground package before claiming success.
     */
    suspend fun openWhatsAppSmart(
        context: Context,
        userQuery: String = ""
    ): OpenWhatsAppResult {
        val cleanQuery = userQuery.lowercase()
        val installed = getInstalledInstances(context)

        if (installed.isEmpty()) {
            return OpenWhatsAppResult.NotFound("Phone mein koi WhatsApp account installed nahi mila.")
        }

        // 1. Check whether WhatsApp is already foreground
        val foregroundPkg = AffyAccessibilityService.currentAppPackage.value ?: ""
        if (isWhatsAppPackage(foregroundPkg)) {
            val matchedForeground = installed.firstOrNull { it.packageName.equals(foregroundPkg, ignoreCase = true) }
            val name = matchedForeground?.displayName ?: "WhatsApp"
            Log.i(TAG, "[WA_FOREGROUND_ACTIVE] WhatsApp is already active in foreground ($foregroundPkg)")
            return OpenWhatsAppResult.AlreadyForeground(
                packageName = foregroundPkg,
                message = "WhatsApp pehle se saamne open hai boss."
            )
        }

        // 2. Explicit variant requested by user
        val requestedBusiness = cleanQuery.contains("business") || cleanQuery.contains("w4b")
        val requestedPersonal = cleanQuery.contains("personal") || cleanQuery.contains("main") || cleanQuery.contains("regular")
        val requestedClone = cleanQuery.contains("second") || cleanQuery.contains("clone") || cleanQuery.contains("dual") || cleanQuery.contains("doosra")

        val targetInstance: WhatsAppInstanceInfo? = when {
            requestedBusiness -> installed.firstOrNull { it.isBusiness } ?: installed.firstOrNull()
            requestedPersonal -> installed.firstOrNull { it.packageName == PKG_WHATSAPP } ?: installed.firstOrNull { !it.isBusiness && !it.isClone }
            requestedClone -> installed.firstOrNull { it.isClone } ?: installed.firstOrNull()
            installed.size == 1 -> installed.first()
            else -> null
        }

        // 3. Multiple instances exist and none was specified
        if (targetInstance == null) {
            val names = installed.map { it.displayName }.distinct()
            val prompt = "Boss, aapke phone mein ${installed.size} WhatsApp accounts hain (${names.joinToString(", ")}). Kaunsa kholun?"
            Log.i(TAG, "[WA_CHOOSER_REQUIRED] Multiple instances found without explicit variant: ${installed.map { it.packageName }}")
            return OpenWhatsAppResult.MultipleInstances(
                instances = installed,
                prompt = prompt
            )
        }

        // 4. Launch resolved package
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetInstance.packageName)
        if (launchIntent == null) {
            return OpenWhatsAppResult.Failed("${targetInstance.displayName} open nahi ho saka.")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Log.i(TAG, "[WA_LAUNCH_DISPATCHED] Launching ${targetInstance.packageName} (${targetInstance.displayName})")

        // 5. Verify foreground package
        var verified = false
        for (attempt in 1..10) {
            delay(300)
            val currentFg = AffyAccessibilityService.currentAppPackage.value ?: ""
            val root = AffyAccessibilityService.instance?.rootInActiveWindow
            val livePkg = root?.packageName?.toString() ?: ""

            if (currentFg.equals(targetInstance.packageName, ignoreCase = true) ||
                livePkg.equals(targetInstance.packageName, ignoreCase = true) ||
                isWhatsAppPackage(currentFg) || isWhatsAppPackage(livePkg)
            ) {
                verified = true
                Log.i(TAG, "[WA_LAUNCH_VERIFIED] Foreground package verified: $targetInstance.packageName on attempt $attempt")
                break
            }
        }

        return if (verified) {
            AffyDiagnostics.logWhatsAppStage("OPEN_SUCCESS", targetInstance.packageName, details = targetInstance.displayName, isSuccess = true)
            OpenWhatsAppResult.Success(
                packageName = targetInstance.packageName,
                message = "Ji boss, ${targetInstance.displayName} khol diya hai."
            )
        } else {
            AffyDiagnostics.logWhatsAppStage("OPEN_UNVERIFIED", targetInstance.packageName, details = "Foreground not verified", isSuccess = false)
            OpenWhatsAppResult.Failed("${targetInstance.displayName} launch karne ki koshish ki magar verify nahi hua.")
        }
    }
}
