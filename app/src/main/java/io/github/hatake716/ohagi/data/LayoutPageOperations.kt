package io.github.hatake716.ohagi.data

/** 指定ページが存在するところまで、上限内で空ページを補う。 */
private fun LayoutState.ensureHomePage(page: Int): LayoutState {
    if (page !in 0 until LayoutState.MAX_HOME_PAGE_COUNT) return this
    val requiredCells = (page + 1) * LayoutState.HOME_CELL_COUNT
    if (home.size >= requiredCells) return this
    return copy(home = home + List(requiredCells - home.size) { null })
}

private fun LayoutState.dropCellOnPage(page: Int): Int? {
    if (page !in 0 until homePageCount) return null
    val start = page * LayoutState.HOME_CELL_COUNT
    val range = start until start + LayoutState.HOME_CELL_COUNT
    return range.firstOrNull { home[it] == null } ?: range.first
}

/** ページ切替でセルtargetが再構成されても、アプリ1件を目的ページへ原子的に移す。 */
internal fun LayoutState.moveAppToHomePage(
    page: Int,
    source: AppMoveSource,
): LayoutState {
    val expanded = ensureHomePage(page)
    val target = expanded.dropCellOnPage(page) ?: return this
    return expanded.moveAppToHome(target, source)
}

/** フォルダを含むホームのトップレベル項目を目的ページへ移動／入れ替えする。 */
internal fun LayoutState.moveHomeItemToHomePage(
    page: Int,
    sourceIndex: Int,
): LayoutState {
    val source = home.getOrNull(sourceIndex) ?: return this
    val expanded = ensureHomePage(page)
    val target = expanded.dropCellOnPage(page) ?: return this
    if (sourceIndex == target) return expanded
    val targetItem = expanded.home[target]
    return expanded.copy(
        home = expanded.home.toMutableList().apply {
            this[sourceIndex] = targetItem
            this[target] = source
        },
    )
}

/** フォルダを含むDock項目を目的ページへ移動／入れ替えする。 */
internal fun LayoutState.moveDockItemToHomePage(
    page: Int,
    dockSlot: Int,
): LayoutState {
    val expanded = ensureHomePage(page)
    val target = expanded.dropCellOnPage(page) ?: return this
    return expanded.moveDockItemToHome(dockSlot, target)
}

/**
 * 先頭ページは空でも必ず残し、項目が0件になった追加ページをすべて取り除く。
 * 中間ページが空になった場合も、後続のページをセル位置ごと左へ詰める。
 */
internal fun LayoutState.withoutEmptyAdditionalHomePages(): LayoutState {
    val pages = List(homePageCount) { page -> homePage(page) }
    val compacted = buildList {
        addAll(pages.first())
        pages.drop(1).forEach { page ->
            if (page.any { it != null }) addAll(page)
        }
    }
    return if (compacted == home) this else copy(home = compacted)
}

internal fun LayoutState.withWidgetMoved(appWidgetId: Int, direction: Int): LayoutState {
    val from = widgets.indexOfFirst { it.appWidgetId == appWidgetId }
    if (from < 0) return this
    val to = (from + direction).coerceIn(0, widgets.lastIndex)
    if (from == to) return this
    return copy(
        widgets = widgets.toMutableList().apply {
            add(to, removeAt(from))
        },
    )
}
