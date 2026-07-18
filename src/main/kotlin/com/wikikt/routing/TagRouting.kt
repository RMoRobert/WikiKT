package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Browse pages by tag at `/t/{tag}`. Lists the published pages carrying that tag which the current
 * user may view (mirrors the search route's permission handling). Tags are stored lowercased.
 */
fun Route.configureTagRouting() {
    get("/t/{tag}") {
        val ctx = call.appContext
        val siteId = call.siteId()
        val userId = call.currentUserId()
        val tag = call.parameters["tag"].orEmpty().trim()
        val localeParam = call.request.queryParameters["locale"]?.takeIf { it.isNotBlank() }

        val pages = ctx.pages.pagesByTag(siteId, tag, localeParam)
            .filter { ctx.permissions.canViewPage(userId, it) }
        val username = userId?.let { ctx.users.findById(it)?.username }

        call.respond(
            MustacheContent(
                "tag.hbs",
                mapOf(
                    "tag" to tag.lowercase(),
                    "resultCount" to pages.size,
                    "hasResults" to pages.isNotEmpty(),
                    "results" to pages.map {
                        mapOf(
                            "title" to it.title,
                            "url" to wikiViewUrl(it.locale, it.path),
                            "description" to it.description,
                            "hasDescription" to !it.description.isNullOrBlank(),
                            "locale" to it.locale,
                        )
                    },
                    "loggedIn" to (userId != null),
                    "canAdmin" to ctx.permissions.canAccessAdmin(userId),
                    "username" to username,
                    "canCreate" to ctx.permissions.canCreatePagesOnSite(userId, siteId),
                    // Keep the header search box scoped to the relevant locale.
                    "searchLocale" to (localeParam ?: ctx.config.defaultLocale),
                ) + call.navModel(),
            ),
        )
    }
}
