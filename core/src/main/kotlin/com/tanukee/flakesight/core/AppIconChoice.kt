package com.tanukee.flakesight.core

/**
 * The app icons the user can pick in About: the mountain through a goggle strap
 * (the default since 1.3), the goggles of 1.2 and the snowy scene the app was
 * released with. Port of `AppIconChoice`.
 *
 * Android has no alternate icon names: each icon is a launcher activity alias,
 * and [alternateIconName] is that alias's class name, `null` for the default
 * one as on iOS. Names and previews are the app's resources.
 */
enum class AppIconChoice(val alias: String) {
    MOUNTAIN("LauncherMountain"),
    GOGGLES("LauncherGoggles"),
    CLASSIC("LauncherClassic"),
    ;

    /** The alias to enable, `null` for the default icon. */
    val alternateIconName: String? get() = alias.takeIf { this != DEFAULT }

    companion object {
        /** The choice shown first and used when nothing was picked. */
        val DEFAULT = MOUNTAIN

        /** The choice matching the enabled alias; an unknown name falls back to the default. */
        fun fromAlternateIconName(name: String?): AppIconChoice = entries.firstOrNull { it.alternateIconName == name } ?: DEFAULT
    }
}
