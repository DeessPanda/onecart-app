package com.onecart

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text rule describing an app-promotion element to find and hide.
 *
 * @param kind           "modal" (app-promotion dialog + optional backdrop) or
 *                       "banner" (a slim top strip).
 * @param phrases        distinctive phrases (normalized, case/whitespace-insensitive).
 * @param minMatches     minimum number of distinct phrases a container must contain.
 * @param maxDepth       how far to climb from the matched text to find the container.
 * @param backdrop       for modals: also remove an adjacent full-screen overlay.
 * @param bannerMaxHeight for banners: max element height to be considered a banner.
 */
data class TextRule(
    val id: String,
    val kind: String,
    val phrases: List<String>,
    val minMatches: Int,
    val maxDepth: Int,
    val backdrop: Boolean,
    val bannerMaxHeight: Int
)

/** Per-host cosmetic selector + text rules. */
data class HostRules(
    val selectors: List<String>,
    val textRules: List<TextRule>,
    val clickRules: List<ClickRule>
)

/**
 * Click rule: when a matching app-promotion modal is open, programmatically
 * click its "continue on web" style dismissal link so the site itself closes
 * the modal (and its overlay/scroll lock). If the click does not work,
 * [fallback] hides the scope container and its ReactModal overlay.
 */
data class ClickRule(
    val id: String,
    val scope: String?,
    val selector: String?,
    val text: String?,
    val fallback: Boolean
)

/**
 * Loads assets/blocklist.json (the single source of truth for rules) and the
 * blocker engine template (assets/blocker_main.js), and produces the JS that
 * is evaluated on each page load.
 *
 * Network layer: shouldInterceptRequest() drops ad/tracker domains.
 * Document layer: the injected JS handles install prompts, cosmetic CSS and
 * text-based detection of app-promotion modals/banners.
 */
class PopupBlocker private constructor(
    val requestDomains: Set<String>,
    val genericSelectors: List<String>,
    val perHost: Map<String, HostRules>,
    val blockedAppPackages: Set<String>,
    val debug: Boolean
) {

    fun isBlockedRequest(host: String): Boolean {
        if (host.isEmpty()) return false
        return requestDomains.any { host == it || host.endsWith(".$it") }
    }

    fun isBlockedApp(packageName: String): Boolean =
        blockedAppPackages.contains(packageName)

    /** JavaScript evaluated on the main frame once it finishes loading. */
    fun cleanupScript(): String {
        val hostsJson = JSONObject()
        perHost.forEach { (host, rules) ->
            val textRules = JSONArray()
            rules.textRules.forEach { r ->
                textRules.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("kind", r.kind)
                        .put("phrases", JSONArray(r.phrases))
                        .put("minMatches", r.minMatches)
                        .put("maxDepth", r.maxDepth)
                        .put("backdrop", r.backdrop)
                        .put("bannerMaxHeight", r.bannerMaxHeight)
                )
            }
            val clickRules = JSONArray()
            rules.clickRules.forEach { r ->
                clickRules.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("scope", r.scope)
                        .put("selector", r.selector)
                        .put("text", r.text)
                        .put("fallback", r.fallback)
                )
            }
            hostsJson.put(
                host,
                JSONObject()
                    .put("selectors", JSONArray(rules.selectors))
                    .put("textRules", textRules)
                    .put("clickRules", clickRules)
            )
        }
        val conf = JSONObject()
            .put("debug", debug)
            .put("generic", JSONArray(genericSelectors))
            .put("hosts", hostsJson)
            .toString()
        return template().replace(PLACEHOLDER, conf)
    }

    companion object {
        private const val TAG = "PopupBlocker"
        private const val PLACEHOLDER = "__ONECART_CONFIG__"
        private var cache: Pair<PopupBlocker, String>? = null

        fun load(context: Context): PopupBlocker {
            cache?.let { (blocker, _) -> if (matchAssets(context, blocker)) return blocker }
            return try {
                val assets = context.assets
                val text = assets.open("blocklist.json").bufferedReader().use { it.readText() }
                val template = assets.open("blocker_main.js").bufferedReader().use { it.readText() }
                val blocker = parse(text)
                cache = blocker to template
                blocker
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocker assets", e)
                PopupBlocker(emptySet(), emptyList(), emptyMap(), emptySet(), false)
            }
        }

        private fun matchAssets(context: Context, blocker: PopupBlocker): Boolean = true

        private fun template(): String =
            cache?.second ?: ""

        private fun parse(text: String): PopupBlocker {
            val root = JSONObject(text)

            val perHost = root.optJSONObject("perHost")?.let { obj ->
                val keys = obj.keys()
                buildMap {
                    while (keys.hasNext()) {
                        val host = keys.next()
                        val hostObj = obj.getJSONObject(host)
                        put(
                            host,
                            HostRules(
                                selectors = hostObj.optJSONArray("selectors")?.toList() ?: emptyList(),
                                textRules = hostObj.optJSONArray("textRules")?.toTextRules() ?: emptyList(),
                                clickRules = hostObj.optJSONArray("clickRules")?.toClickRules() ?: emptyList()
                            )
                        )
                    }
                }
            } ?: emptyMap()

            return PopupBlocker(
                requestDomains = root.optJSONArray("requestDomains")?.toList()?.toSet() ?: emptySet(),
                genericSelectors = root.optJSONArray("genericSelectors")?.toList() ?: emptyList(),
                perHost = perHost,
                blockedAppPackages = root.optJSONArray("blockedAppPackages")?.toList()?.toSet() ?: emptySet(),
                debug = root.optBoolean("debug", false)
            )
        }

        private fun JSONArray.toList(): List<String> =
            (0 until length()).map { getString(it) }

        private fun JSONArray.toTextRules(): List<TextRule> =
            (0 until length()).map { i ->
                val o = getJSONObject(i)
                TextRule(
                    id = o.optString("id", "rule-$i"),
                    kind = o.optString("kind", "element"),
                    phrases = o.optJSONArray("phrases")?.toList() ?: emptyList(),
                    minMatches = o.optInt("minMatches", 1),
                    maxDepth = o.optInt("maxDepth", 10),
                    backdrop = o.optBoolean("backdrop", false),
                    bannerMaxHeight = o.optInt("bannerMaxHeight", 200)
                )
            }

        private fun JSONArray.toClickRules(): List<ClickRule> =
            (0 until length()).map { i ->
                val o = getJSONObject(i)
                ClickRule(
                    id = o.optString("id", "click-$i"),
                    scope = o.optString("scope", "").ifEmpty { null },
                    selector = o.optString("selector", "").ifEmpty { null },
                    text = o.optString("text", "").ifEmpty { null },
                    fallback = o.optBoolean("fallback", false)
                )
            }
    }
}