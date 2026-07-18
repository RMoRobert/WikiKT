package com.wikikt.service

import com.wikikt.db.NavItemsTable
import com.wikikt.db.NavMenusTable
import com.wikikt.model.NavItemInput
import com.wikikt.model.NavItemRecord
import com.wikikt.model.NavMenuRecord
import com.wikikt.model.toNavItemRecord
import com.wikikt.model.toNavMenuRecord
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class NavService(private val database: R2dbcDatabase) {
    suspend fun listMenus(siteId: UInt): List<NavMenuRecord> = suspendTransaction(database) {
        NavMenusTable.selectAll().where { NavMenusTable.siteId eq siteId }
            .map { it.toNavMenuRecord() }.toList().sortedBy { it.scope }
    }

    suspend fun findMenu(id: UInt): NavMenuRecord? = suspendTransaction(database) {
        NavMenusTable.selectAll().where { NavMenusTable.id eq id }.map { it.toNavMenuRecord() }.singleOrNull()
    }

    suspend fun items(menuId: UInt): List<NavItemRecord> = suspendTransaction(database) {
        NavItemsTable.selectAll()
            .where { NavItemsTable.menuId eq menuId }
            .map { it.toNavItemRecord() }
            .toList()
            .sortedBy { it.position }
    }

    /**
     * The items of the menu that best matches [pagePath]: the non-default menu whose scope is the
     * longest path-prefix of the page, else the default ("") menu, else empty.
     */
    suspend fun itemsForPath(siteId: UInt, pagePath: String): List<NavItemRecord> {
        val menu = menuForPath(siteId, pagePath) ?: return emptyList()
        return items(menu.id)
    }

    /**
     * The menu that governs [pagePath] on [siteId]: the non-default menu whose scope is the longest
     * path-prefix of the page, else the default ("") menu, else null. Drives the on-page "Edit menu".
     */
    suspend fun menuForPath(siteId: UInt, pagePath: String): NavMenuRecord? =
        menuFor(listMenus(siteId), pagePath)

    suspend fun createMenu(siteId: UInt, scope: String, items: List<NavItemInput>): NavMenuRecord = suspendTransaction(database) {
        val id = NavMenusTable.insert {
            it[NavMenusTable.siteId] = siteId
            it[NavMenusTable.scope] = scope
        }[NavMenusTable.id].value
        items.forEachIndexed { index, item -> insertItem(id, index, item) }
        NavMenusTable.selectAll().where { NavMenusTable.id eq id }.map { it.toNavMenuRecord() }.singleOrNull()!!
    }

    suspend fun updateMenu(id: UInt, scope: String, items: List<NavItemInput>): NavMenuRecord? = suspendTransaction(database) {
        NavMenusTable.selectAll().where { NavMenusTable.id eq id }.singleOrNull() ?: return@suspendTransaction null
        NavMenusTable.update({ NavMenusTable.id eq id }) { it[NavMenusTable.scope] = scope }
        NavItemsTable.deleteWhere { NavItemsTable.menuId eq id }
        items.forEachIndexed { index, item -> insertItem(id, index, item) }
        NavMenusTable.selectAll().where { NavMenusTable.id eq id }.map { it.toNavMenuRecord() }.singleOrNull()
    }

    suspend fun deleteMenu(id: UInt): Boolean = suspendTransaction(database) {
        NavItemsTable.deleteWhere { NavItemsTable.menuId eq id }
        NavMenusTable.deleteWhere { NavMenusTable.id eq id } > 0
    }

    private suspend fun insertItem(menuId: UInt, position: Int, item: NavItemInput) {
        NavItemsTable.insert {
            it[NavItemsTable.menuId] = menuId
            it[NavItemsTable.position] = position
            it[isHeader] = item.isHeader
            it[isDivider] = item.isDivider
            it[depth] = item.depth
            it[label] = item.label
            it[icon] = item.icon
            it[target] = item.target
        }
    }

    companion object {
        /**
         * Picks the menu governing [pagePath] out of an already-loaded [menus] list: the non-default
         * menu whose scope is the longest path-prefix of the page, else the default ("") menu. Callers
         * resolving many paths at once load the menus once and match in memory rather than paying a
         * query per path.
         */
        fun menuFor(menus: List<NavMenuRecord>, pagePath: String): NavMenuRecord? = menus
            .filter { it.scope.isNotEmpty() && (pagePath == it.scope || pagePath.startsWith("${it.scope}/")) }
            .maxByOrNull { it.scope.length }
            ?: menus.firstOrNull { it.scope.isEmpty() }

        private val ICON_PREFIX = Regex("^:([a-z0-9-]+):\\s*")

        private val DIVIDER_LINE = Regex("^-{3,}$")

        /** One indent level in the text format. Leading indentation → nesting depth (capped at 1). */
        const val INDENT = "  "
        const val MAX_DEPTH = 1

        /**
         * Parses the editor text format into items. Each non-blank line is one item:
         *  - leading indentation (2 spaces per level, capped at one) nests a link under the link above it
         *  - a line of three or more dashes (`---`) is a divider
         *  - optional `:icon-name:` prefix sets the MDI icon
         *  - `# Label` is a non-link heading
         *  - `Label | /target` is a link
         * Lines that don't fit any shape are skipped. Headers/dividers are always depth 0.
         */
        fun parseDefinition(text: String): List<NavItemInput> = text.lines().mapNotNull { raw ->
            if (raw.isBlank()) return@mapNotNull null
            val indent = raw.takeWhile { it == ' ' }.length
            val depth = (indent / INDENT.length).coerceIn(0, MAX_DEPTH)
            var line = raw.trim()
            if (DIVIDER_LINE.matches(line)) {
                return@mapNotNull NavItemInput(isHeader = false, isDivider = true, depth = 0, label = "", icon = null, target = null)
            }

            var icon: String? = null
            ICON_PREFIX.find(line)?.let { match ->
                icon = match.groupValues[1]
                line = line.substring(match.range.last + 1).trim()
            }

            if (line.startsWith("#")) {
                val label = line.removePrefix("#").trim()
                if (label.isEmpty()) null
                else NavItemInput(isHeader = true, isDivider = false, depth = 0, label = label, icon = icon, target = null)
            } else {
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val label = parts[0].trim()
                val target = parts[1].trim()
                if (label.isEmpty() || target.isEmpty()) null
                else NavItemInput(isHeader = false, isDivider = false, depth = depth, label = label, icon = icon, target = target)
            }
        }

        /** Serializes stored items back into the editor text format (indenting nested links). */
        fun toDefinition(items: List<NavItemRecord>): String = items.joinToString("\n") { item ->
            val pad = INDENT.repeat(if (item.isHeader || item.isDivider) 0 else item.depth.coerceIn(0, MAX_DEPTH))
            when {
                item.isDivider -> "---"
                item.isHeader -> "${item.icon?.let { ":$it: " } ?: ""}# ${item.label}"
                else -> "$pad${item.icon?.let { ":$it: " } ?: ""}${item.label} | ${item.target}"
            }
        }
    }
}
