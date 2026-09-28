package com.littletaro.vesna.core

import android.content.Context

/** A saved special-tuning profile. Profiles are keyed by the game's package name. */
data class GameSpecialConfig(
    val autoReturnEnabled: Boolean = false,
    val autoReturnDelayMs: Long = 10_000L,
    val staminaAutoSwitchEnabled: Boolean = false,
    val staminaThresholdPercent: Int = 25,
    val staminaConfirmFrames: Int = 2,
)

/**
 * Per-game settings and the single currently selected game profile.
 * Adding a game only requires opening GameOptimizerActivity with its package and label.
 */
object GameSpecialPrefs {
    const val EXTRA_GAME_PACKAGE = "game_special_package"
    const val EXTRA_GAME_LABEL = "game_special_label"

    private const val PREFS_NAME = "vesna_game_special_profiles"
    private const val KEY_ACTIVE_PACKAGE = "active_package"
    private const val KEY_LEGACY_MIGRATED = "legacy_migrated"
    private const val PROFILE_PREFIX = "profile."

    private val genshinPackages = listOf(
        "com.miHoYo.Yuanshen",
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.ys.mihoyo",
    )

    fun resolveGenshinPackage(context: Context): String {
        val active = activePackage(context)
        if (active != null && active in genshinPackages) return active
        return genshinPackages.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: genshinPackages.first()
    }

    fun activePackage(context: Context): String? {
        migrateLegacySettings(context)
        return preferences(context).getString(KEY_ACTIVE_PACKAGE, null)?.takeIf { it.isNotBlank() }
    }

    fun isActive(context: Context, packageName: String): Boolean =
        activePackage(context) == packageName

    /** Selecting one game replaces the previous selection; null returns to normal mode. */
    fun setActivePackage(context: Context, packageName: String?) {
        migrateLegacySettings(context)
        val editor = preferences(context).edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(KEY_ACTIVE_PACKAGE)
        } else {
            editor.putString(KEY_ACTIVE_PACKAGE, packageName)
        }
        editor.apply()
    }

    fun load(context: Context, packageName: String): GameSpecialConfig {
        require(packageName.isNotBlank()) { "Game package name must not be blank" }
        migrateLegacySettings(context)
        val prefs = preferences(context)
        val defaults = GameSpecialConfig()
        return GameSpecialConfig(
            autoReturnEnabled = prefs.getBoolean(key(packageName, "auto_return_enabled"), defaults.autoReturnEnabled),
            autoReturnDelayMs = prefs.getLong(key(packageName, "auto_return_delay_ms"), defaults.autoReturnDelayMs),
            staminaAutoSwitchEnabled = prefs.getBoolean(
                key(packageName, "stamina_auto_switch_enabled"),
                defaults.staminaAutoSwitchEnabled,
            ),
            staminaThresholdPercent = prefs.getInt(
                key(packageName, "stamina_threshold_percent"),
                defaults.staminaThresholdPercent,
            ),
            staminaConfirmFrames = prefs.getInt(
                key(packageName, "stamina_confirm_frames"),
                defaults.staminaConfirmFrames,
            ),
        )
    }

    fun save(context: Context, packageName: String, config: GameSpecialConfig) {
        require(packageName.isNotBlank()) { "Game package name must not be blank" }
        migrateLegacySettings(context)
        writeProfile(preferences(context), packageName, config)
    }

    private fun migrateLegacySettings(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return

        synchronized(this) {
            if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return

            val legacy = OverlayPrefs.load(context)
            val genshinPackage = genshinPackages.firstOrNull {
                context.packageManager.getLaunchIntentForPackage(it) != null
            } ?: genshinPackages.first()
            val profileExists = prefs.contains(key(genshinPackage, "auto_return_enabled"))
            val editor = prefs.edit()
            if (!profileExists) {
                writeProfile(
                    editor,
                    genshinPackage,
                    GameSpecialConfig(
                        autoReturnEnabled = legacy.autoReturnEnabled,
                        autoReturnDelayMs = legacy.autoReturnDelayMs,
                        staminaAutoSwitchEnabled = legacy.staminaAutoSwitchEnabled,
                        staminaThresholdPercent = legacy.staminaThresholdPercent,
                        staminaConfirmFrames = legacy.staminaConfirmFrames,
                    ),
                )
            }
            if ((legacy.autoReturnEnabled || legacy.staminaAutoSwitchEnabled) &&
                !prefs.contains(KEY_ACTIVE_PACKAGE)
            ) {
                editor.putString(KEY_ACTIVE_PACKAGE, genshinPackage)
            }
            editor.putBoolean(KEY_LEGACY_MIGRATED, true).apply()
        }
    }

    private fun writeProfile(
        prefs: android.content.SharedPreferences,
        packageName: String,
        config: GameSpecialConfig,
    ) = writeProfile(prefs.edit(), packageName, config).apply()

    private fun writeProfile(
        editor: android.content.SharedPreferences.Editor,
        packageName: String,
        config: GameSpecialConfig,
    ): android.content.SharedPreferences.Editor = editor
        .putBoolean(key(packageName, "auto_return_enabled"), config.autoReturnEnabled)
        .putLong(key(packageName, "auto_return_delay_ms"), config.autoReturnDelayMs.coerceIn(2_000L, 15_000L))
        .putBoolean(key(packageName, "stamina_auto_switch_enabled"), config.staminaAutoSwitchEnabled)
        .putInt(key(packageName, "stamina_threshold_percent"), config.staminaThresholdPercent.coerceIn(5, 60))
        .putInt(key(packageName, "stamina_confirm_frames"), config.staminaConfirmFrames.coerceIn(1, 5))

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(packageName: String, field: String) = "$PROFILE_PREFIX$packageName.$field"
}
