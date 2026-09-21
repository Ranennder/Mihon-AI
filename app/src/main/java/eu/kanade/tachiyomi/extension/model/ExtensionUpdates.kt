package eu.kanade.tachiyomi.extension.model

/** Use the same APK for update detection and installation when stores contain the same package. */
internal fun List<Extension.Available>.withLatestVersions(): List<Extension.Available> {
    val versionComparator = compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion }
    return groupBy { it.pkgName }.values.map { it.maxWith(versionComparator) }
}
