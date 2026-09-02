package io.github.hatake716.ohagi.data

/**
 * フォルダを含むレイアウト変更の純粋関数群。
 *
 * LayoutRepositoryの1回のupdateData内で、取得元の除去と移動先の更新を同時に行う。
 * これにより、フォルダ内外やホーム／Dock間のD&Dでも中間状態を永続化しない。
 */
internal fun LayoutState.moveAppToHome(
    targetIndex: Int,
    source: AppMoveSource,
): LayoutState {
    if (targetIndex !in home.indices) return this
    if (source is AppMoveSource.Home && source.index == targetIndex) return this
    if (source is AppMoveSource.HomeFolder && source.index == targetIndex) return this

    val editor = LayoutEditor(this)
    val sourceApp = editor.appAt(source) ?: return this
    when (val target = editor.home[targetIndex]) {
        null -> {
            if (!editor.removeApp(source)) return this
            editor.home[targetIndex] = HomeItem.HomeApp(sourceApp)
        }

        // ファイル/フォルダのピンの上にはアプリを置かない(no-op)。
        is HomeItem.HomeFile, is HomeItem.HomeDirectory -> return this

        is HomeItem.HomeFolder -> {
            if (!editor.removeApp(source)) return this
            editor.home[targetIndex] = target.copy(
                apps = (target.apps + sourceApp).distinct(),
            )
        }

        is HomeItem.HomeApp -> when (source) {
            is AppMoveSource.External -> {
                editor.home[targetIndex] = HomeItem.HomeApp(sourceApp)
            }

            is AppMoveSource.Home -> {
                editor.home[source.index] = HomeItem.HomeApp(target.app)
                editor.home[targetIndex] = HomeItem.HomeApp(sourceApp)
            }

            is AppMoveSource.Dock -> {
                editor.dock[source.slot] = DockItem.DockApp(target.app)
                editor.home[targetIndex] = HomeItem.HomeApp(sourceApp)
            }

            is AppMoveSource.HomeFolder,
            is AppMoveSource.DockFolder,
                -> {
                    if (!editor.replaceApp(source, target.app)) return this
                    editor.home[targetIndex] = HomeItem.HomeApp(sourceApp)
                }
        }
    }
    return editor.build()
}

internal fun LayoutState.moveAppToDock(
    targetSlot: Int,
    source: AppMoveSource,
): LayoutState {
    if (targetSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return this
    if (source is AppMoveSource.Dock && source.slot == targetSlot) return this
    if (source is AppMoveSource.DockFolder && source.slot == targetSlot) return this

    val editor = LayoutEditor(this)
    val sourceApp = editor.appAt(source) ?: return this
    when (val target = editor.dock[targetSlot]) {
        null -> {
            if (!editor.removeApp(source)) return this
            editor.dock[targetSlot] = DockItem.DockApp(sourceApp)
        }

        is DockItem.DockFolder -> {
            if (!editor.removeApp(source)) return this
            editor.dock[targetSlot] = target.copy(
                apps = (target.apps + sourceApp).distinct(),
            )
        }

        is DockItem.DockApp -> when (source) {
            is AppMoveSource.External -> {
                editor.dock[targetSlot] = DockItem.DockApp(sourceApp)
            }

            is AppMoveSource.Dock -> {
                editor.dock[source.slot] = DockItem.DockApp(target.app)
                editor.dock[targetSlot] = DockItem.DockApp(sourceApp)
            }

            is AppMoveSource.Home -> {
                editor.home[source.index] = HomeItem.HomeApp(target.app)
                editor.dock[targetSlot] = DockItem.DockApp(sourceApp)
            }

            is AppMoveSource.HomeFolder,
            is AppMoveSource.DockFolder,
                -> {
                    if (!editor.replaceApp(source, target.app)) return this
                    editor.dock[targetSlot] = DockItem.DockApp(sourceApp)
                }
        }
    }
    return editor.build()
}

/** アプリをホーム上のアプリへ重ねてフォルダ化するか、既存フォルダへ追加する。 */
internal fun LayoutState.stackAppOnHome(
    targetIndex: Int,
    source: AppMoveSource,
    folderName: String,
): LayoutState {
    if (targetIndex !in home.indices) return this
    if (source is AppMoveSource.Home && source.index == targetIndex) return this
    if (source is AppMoveSource.HomeFolder && source.index == targetIndex) return this

    val target = home[targetIndex]
    if (target == null) return moveAppToHome(targetIndex, source)

    val editor = LayoutEditor(this)
    val sourceApp = editor.appAt(source) ?: return this
    when (target) {
        // ファイル/フォルダのピンとはフォルダを作らない(no-op)。
        is HomeItem.HomeFile, is HomeItem.HomeDirectory -> return this

        is HomeItem.HomeFolder -> {
            if (!editor.removeApp(source)) return this
            editor.home[targetIndex] = target.copy(
                apps = (target.apps + sourceApp).distinct(),
            )
        }

        is HomeItem.HomeApp -> {
            if (target.app == sourceApp) return this
            if (!editor.removeApp(source)) return this
            editor.home[targetIndex] = HomeItem.HomeFolder(
                name = folderName.ifBlank { DEFAULT_FOLDER_NAME },
                apps = listOf(target.app, sourceApp).distinct(),
            )
        }
    }
    return editor.build()
}

/** アプリをDock上のアプリへ重ねてフォルダ化するか、既存フォルダへ追加する。 */
internal fun LayoutState.stackAppOnDock(
    targetSlot: Int,
    source: AppMoveSource,
    folderName: String,
): LayoutState {
    if (targetSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return this
    if (source is AppMoveSource.Dock && source.slot == targetSlot) return this
    if (source is AppMoveSource.DockFolder && source.slot == targetSlot) return this

    val target = dock[targetSlot]
    if (target == null) return moveAppToDock(targetSlot, source)

    val editor = LayoutEditor(this)
    val sourceApp = editor.appAt(source) ?: return this
    when (target) {
        is DockItem.DockFolder -> {
            if (!editor.removeApp(source)) return this
            editor.dock[targetSlot] = target.copy(
                apps = (target.apps + sourceApp).distinct(),
            )
        }

        is DockItem.DockApp -> {
            if (target.app == sourceApp) return this
            if (!editor.removeApp(source)) return this
            editor.dock[targetSlot] = DockItem.DockFolder(
                name = folderName.ifBlank { DEFAULT_FOLDER_NAME },
                apps = listOf(target.app, sourceApp).distinct(),
            )
        }
    }
    return editor.build()
}

/** トップレベルのアプリまたはフォルダをホームからDockへ移動／入れ替えする。 */
internal fun LayoutState.moveHomeItemToDock(
    homeIndex: Int,
    dockSlot: Int,
): LayoutState {
    if (homeIndex !in home.indices) return this
    if (dockSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return this
    val source = home[homeIndex] ?: return this
    // ファイル/フォルダのピンはホーム専用。Dock へは移動させない(no-op)。
    if (source is HomeItem.HomeFile || source is HomeItem.HomeDirectory) return this
    val target = dock[dockSlot]
    return copy(
        home = home.toMutableList().apply {
            this[homeIndex] = target?.toHomeItem()
        },
        dock = dock.toMutableList().apply {
            this[dockSlot] = source.toDockItem()
        },
    )
}

/** トップレベルのアプリまたはフォルダをDockからホームへ移動／入れ替えする。 */
internal fun LayoutState.moveDockItemToHome(
    dockSlot: Int,
    homeIndex: Int,
): LayoutState {
    if (homeIndex !in home.indices) return this
    if (dockSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return this
    val source = dock[dockSlot] ?: return this
    val target = home[homeIndex]
    // 入替先がファイル/フォルダのピンなら no-op(ピンが Dock 側へ流れるのを防ぐ)。
    if (target is HomeItem.HomeFile || target is HomeItem.HomeDirectory) return this
    return copy(
        home = home.toMutableList().apply {
            this[homeIndex] = source.toHomeItem()
        },
        dock = dock.toMutableList().apply {
            this[dockSlot] = target?.toDockItem()
        },
    )
}

internal fun LayoutState.createOrAddFolder(
    location: FolderLocation,
    name: String,
    addedApps: List<AppRef>,
): LayoutState {
    val additions = addedApps.distinct()
    if (additions.isEmpty()) return this
    return when (location) {
        is FolderLocation.Home -> {
            if (location.index !in home.indices) return this
            val updated = when (val current = home[location.index]) {
                null -> folderOrApp(name, additions)
                is HomeItem.HomeApp ->
                    folderOrApp(name, listOf(current.app) + additions)
                is HomeItem.HomeFolder ->
                    current.copy(apps = (current.apps + additions).distinct())
                // ファイル/フォルダのピンはアプリフォルダにしない(no-op)。
                is HomeItem.HomeFile, is HomeItem.HomeDirectory -> return this
            }
            copy(home = home.toMutableList().apply { this[location.index] = updated })
        }

        is FolderLocation.Dock -> {
            if (location.slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return this
            val updated = when (val current = dock[location.slot]) {
                null -> dockFolderOrApp(name, additions)
                is DockItem.DockApp ->
                    dockFolderOrApp(name, listOf(current.app) + additions)
                is DockItem.DockFolder ->
                    current.copy(apps = (current.apps + additions).distinct())
            }
            copy(dock = dock.toMutableList().apply { this[location.slot] = updated })
        }
    }
}

internal fun LayoutState.addAppsToFolder(
    location: FolderLocation,
    addedApps: List<AppRef>,
): LayoutState {
    val additions = addedApps.distinct()
    if (additions.isEmpty()) return this
    return when (location) {
        is FolderLocation.Home -> {
            val folder = home.getOrNull(location.index) as? HomeItem.HomeFolder ?: return this
            copy(
                home = home.toMutableList().apply {
                    this[location.index] = folder.copy(
                        apps = (folder.apps + additions).distinct(),
                    )
                },
            )
        }

        is FolderLocation.Dock -> {
            val folder = dock.getOrNull(location.slot) as? DockItem.DockFolder ?: return this
            copy(
                dock = dock.toMutableList().apply {
                    this[location.slot] = folder.copy(
                        apps = (folder.apps + additions).distinct(),
                    )
                },
            )
        }
    }
}

internal fun LayoutState.removeAppFromFolder(
    location: FolderLocation,
    app: AppRef,
): LayoutState = when (location) {
    is FolderLocation.Home -> {
        val folder = home.getOrNull(location.index) as? HomeItem.HomeFolder ?: return this
        val remaining = folder.apps.toMutableList().apply { remove(app) }
        copy(
            home = home.toMutableList().apply {
                this[location.index] = if (remaining.isEmpty()) null else folder.copy(apps = remaining)
            },
        )
    }

    is FolderLocation.Dock -> {
        val folder = dock.getOrNull(location.slot) as? DockItem.DockFolder ?: return this
        val remaining = folder.apps.toMutableList().apply { remove(app) }
        copy(
            dock = dock.toMutableList().apply {
                this[location.slot] = if (remaining.isEmpty()) null else folder.copy(apps = remaining)
            },
        )
    }
}

internal fun LayoutState.renameFolder(
    location: FolderLocation,
    name: String,
): LayoutState = when (location) {
    is FolderLocation.Home -> {
        val folder = home.getOrNull(location.index) as? HomeItem.HomeFolder ?: return this
        copy(
            home = home.toMutableList().apply {
                this[location.index] = folder.copy(name = name.ifBlank { folder.name })
            },
        )
    }

    is FolderLocation.Dock -> {
        val folder = dock.getOrNull(location.slot) as? DockItem.DockFolder ?: return this
        copy(
            dock = dock.toMutableList().apply {
                this[location.slot] = folder.copy(name = name.ifBlank { folder.name })
            },
        )
    }
}

internal fun LayoutState.reorderFolderApps(
    location: FolderLocation,
    fromIndex: Int,
    toIndex: Int,
): LayoutState {
    if (fromIndex == toIndex) return this

    fun reordered(apps: List<AppRef>): List<AppRef>? {
        if (fromIndex !in apps.indices || toIndex !in apps.indices) return null
        return apps.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
    }

    return when (location) {
        is FolderLocation.Home -> {
            val folder = home.getOrNull(location.index) as? HomeItem.HomeFolder ?: return this
            val apps = reordered(folder.apps) ?: return this
            copy(
                home = home.toMutableList().apply {
                    this[location.index] = folder.copy(apps = apps)
                },
            )
        }

        is FolderLocation.Dock -> {
            val folder = dock.getOrNull(location.slot) as? DockItem.DockFolder ?: return this
            val apps = reordered(folder.apps) ?: return this
            copy(
                dock = dock.toMutableList().apply {
                    this[location.slot] = folder.copy(apps = apps)
                },
            )
        }
    }
}

private class LayoutEditor(private val sourceState: LayoutState) {
    val home = sourceState.home.toMutableList()
    val dock = sourceState.dock.toMutableList()

    fun appAt(source: AppMoveSource): AppRef? = when (source) {
        is AppMoveSource.Home ->
            (home.getOrNull(source.index) as? HomeItem.HomeApp)?.app
        is AppMoveSource.Dock ->
            (dock.getOrNull(source.slot) as? DockItem.DockApp)?.app
        is AppMoveSource.HomeFolder -> {
            val folder = home.getOrNull(source.index) as? HomeItem.HomeFolder
            folder?.appIndex(source.appIndex, source.expectedApp)
                ?.let(folder.apps::get)
        }
        is AppMoveSource.DockFolder -> {
            val folder = dock.getOrNull(source.slot) as? DockItem.DockFolder
            folder?.appIndex(source.appIndex, source.expectedApp)
                ?.let(folder.apps::get)
        }
        is AppMoveSource.External -> source.app
    }

    fun removeApp(source: AppMoveSource): Boolean = when (source) {
        is AppMoveSource.Home -> {
            if (home.getOrNull(source.index) !is HomeItem.HomeApp) false
            else {
                home[source.index] = null
                true
            }
        }
        is AppMoveSource.Dock -> {
            if (dock.getOrNull(source.slot) !is DockItem.DockApp) false
            else {
                dock[source.slot] = null
                true
            }
        }
        is AppMoveSource.HomeFolder -> {
            val folder = home.getOrNull(source.index) as? HomeItem.HomeFolder ?: return false
            val index = folder.appIndex(source.appIndex, source.expectedApp) ?: return false
            val remaining = folder.apps.toMutableList().apply { removeAt(index) }
            home[source.index] = if (remaining.isEmpty()) null else folder.copy(apps = remaining)
            true
        }
        is AppMoveSource.DockFolder -> {
            val folder = dock.getOrNull(source.slot) as? DockItem.DockFolder ?: return false
            val index = folder.appIndex(source.appIndex, source.expectedApp) ?: return false
            val remaining = folder.apps.toMutableList().apply { removeAt(index) }
            dock[source.slot] = if (remaining.isEmpty()) null else folder.copy(apps = remaining)
            true
        }
        is AppMoveSource.External -> true
    }

    /** フォルダ内アプリをトップレベルの移動先アプリと入れ替える。 */
    fun replaceApp(source: AppMoveSource, replacement: AppRef): Boolean = when (source) {
        is AppMoveSource.HomeFolder -> {
            val folder = home.getOrNull(source.index) as? HomeItem.HomeFolder ?: return false
            val index = folder.appIndex(source.appIndex, source.expectedApp) ?: return false
            val replaced = folder.apps.toMutableList().apply { this[index] = replacement }.distinct()
            home[source.index] = if (replaced.isEmpty()) null else folder.copy(apps = replaced)
            true
        }
        is AppMoveSource.DockFolder -> {
            val folder = dock.getOrNull(source.slot) as? DockItem.DockFolder ?: return false
            val index = folder.appIndex(source.appIndex, source.expectedApp) ?: return false
            val replaced = folder.apps.toMutableList().apply { this[index] = replacement }.distinct()
            dock[source.slot] = if (replaced.isEmpty()) null else folder.copy(apps = replaced)
            true
        }
        else -> false
    }

    fun build(): LayoutState = sourceState.copy(home = home, dock = dock)
}

/** ファイル/フォルダのピンは Dock へ変換できない(null=置けない)。 */
private fun HomeItem.toDockItem(): DockItem? = when (this) {
    is HomeItem.HomeApp -> DockItem.DockApp(app)
    is HomeItem.HomeFolder -> DockItem.DockFolder(name, apps)
    is HomeItem.HomeFile, is HomeItem.HomeDirectory -> null
}

private fun DockItem.toHomeItem(): HomeItem = when (this) {
    is DockItem.DockApp -> HomeItem.HomeApp(app)
    is DockItem.DockFolder -> HomeItem.HomeFolder(name, apps)
}

private fun List<AppRef>.appIndex(requested: Int, expected: AppRef): Int? = when {
    getOrNull(requested) == expected -> requested
    else -> indexOf(expected).takeIf { it >= 0 }
}

private fun HomeItem.HomeFolder.appIndex(requested: Int, expected: AppRef): Int? =
    apps.appIndex(requested, expected)

private fun DockItem.DockFolder.appIndex(requested: Int, expected: AppRef): Int? =
    apps.appIndex(requested, expected)

private fun folderOrApp(name: String, apps: List<AppRef>): HomeItem? {
    val distinct = apps.distinct()
    return when (distinct.size) {
        0 -> null
        1 -> HomeItem.HomeApp(distinct.single())
        else -> HomeItem.HomeFolder(name.ifBlank { DEFAULT_FOLDER_NAME }, distinct)
    }
}

private fun dockFolderOrApp(name: String, apps: List<AppRef>): DockItem? {
    val distinct = apps.distinct()
    return when (distinct.size) {
        0 -> null
        1 -> DockItem.DockApp(distinct.single())
        else -> DockItem.DockFolder(name.ifBlank { DEFAULT_FOLDER_NAME }, distinct)
    }
}

private const val DEFAULT_FOLDER_NAME = "フォルダ"
