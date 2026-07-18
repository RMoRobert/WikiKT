package com.wikikt

import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.mustache.Mustache

fun Application.configureMustache() {
    install(Mustache) {
        // Resolve partials from the template root rather than relative to the including template's
        // folder, so a shared `{{>partials/header.hbs}}` works from page/, admin/, auth/, etc.
        mustacheFactory = object : DefaultMustacheFactory("templates/mustache") {
            override fun resolvePartialPath(dir: String?, name: String, extension: String): String =
                super.resolvePartialPath("", name, extension)
        }
    }
}
