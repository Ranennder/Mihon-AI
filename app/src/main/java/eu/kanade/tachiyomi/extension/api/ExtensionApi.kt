package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.model.withLatestVersions
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class ExtensionApi(
    private val repository: ExtensionStoreRepository = Injekt.get(),
    private val updateExtensionStores: UpdateExtensionStores = Injekt.get(),
) {

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            // Stores can redirect their legacy index to a new format. Refresh the
            // saved endpoint before browsing too, not just in the scheduled update job.
            updateExtensionStores()
            repository.fetchExtensions().withLatestVersions()
        }
    }

    suspend fun checkForUpdates(context: Context) {
        val extensions = findExtensions()

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }
    }
}
