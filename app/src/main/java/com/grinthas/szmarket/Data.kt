package com.grinthas.szmarket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** Регионы аукциона. Для RU используется русская база предметов, для остальных — global. */
enum class Region(val id: String, val title: String, val realm: String) {
    RU("ru", "Россия", "ru"),
    EU("eu", "Европа", "global"),
    NA("na", "Северная Америка", "global"),
    SEA("sea", "Юго-Восточная Азия", "global"),
    NEA("nea", "Северо-Восточная Азия", "global"),
}

data class Item(
    val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val category: String,
)

data class Lot(
    val amount: Int,
    val startPrice: Long,
    val currentPrice: Long?,
    val buyoutPrice: Long,
    val endTime: Instant,
    val level: Int?,
)

data class Sale(val amount: Int, val price: Long, val time: Instant)

class ApiException(message: String) : Exception(message)

private const val DB = "https://raw.githubusercontent.com/EXBO-Studio/stalzone-database/main"
private const val OAUTH = "https://exbo.net/oauth/token"
const val DEFAULT_API = "https://eapi.stalcraft.net"

private fun http(url: String, method: String = "GET", body: String? = null, token: String? = null): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 15_000
    c.readTimeout = 20_000
    c.setRequestProperty("Accept", "application/json")
    token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    val code = c.responseCode
    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) {
        throw ApiException(
            when (code) {
                401, 403 -> "Ключи API не подходят или нет доступа ($code)"
                404 -> "Не найдено (404)"
                429 -> "Слишком много запросов, подождите минуту"
                else -> "Ошибка сервера $code: ${text.take(200)}"
            }
        )
    }
    return text
}

/** Настройки и избранное. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("market", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("clientId", "").orEmpty()
        set(v) = p.edit().putString("clientId", v.trim()).remove("token").apply()
    var clientSecret: String
        get() = p.getString("clientSecret", "").orEmpty()
        set(v) = p.edit().putString("clientSecret", v.trim()).remove("token").apply()
    var apiUrl: String
        get() = p.getString("apiUrl", DEFAULT_API).orEmpty().ifBlank { DEFAULT_API }.trimEnd('/')
        set(v) = p.edit().putString("apiUrl", v.trim()).apply()
    var region: Region
        get() = Region.entries.firstOrNull { it.id == p.getString("region", "ru") } ?: Region.RU
        set(v) = p.edit().putString("region", v.id).apply()

    val hasKeys: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** Избранное: id предмета → порог цены для уведомления (0 — без уведомления). */
    var favorites: Map<String, Long>
        get() {
            val o = JSONObject(p.getString("favorites", "{}")!!)
            return o.keys().asSequence().associateWith { o.getLong(it) }
        }
        set(v) = p.edit().putString("favorites", JSONObject(v).toString()).apply()

    /** Последний лот, о котором уже уведомили, чтобы не повторяться. */
    fun notified(id: String): Long = p.getLong("notified_$id", 0)
    fun setNotified(id: String, price: Long) = p.edit().putLong("notified_$id", price).apply()

    @Synchronized
    fun token(): String {
        val cached = p.getString("token", null)
        if (cached != null && p.getLong("tokenExpires", 0) > System.currentTimeMillis() + 60_000) return cached
        if (!hasKeys) throw ApiException("Укажите client_id и client_secret в настройках")
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val body = "client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}&grant_type=client_credentials&scope="
        val o = JSONObject(http(OAUTH, "POST", body))
        val token = o.getString("access_token")
        p.edit()
            .putString("token", token)
            .putLong("tokenExpires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
            .apply()
        return token
    }
}

/** Доступ к официальному API аукциона. */
class Api(private val store: Store) {
    private fun get(path: String): JSONObject {
        val url = "${store.apiUrl}/${store.region.id}/$path"
        return JSONObject(http(url, token = store.token()))
    }

    /** sort: time_created | time_left | current_price | buyout_price; order: asc | desc. */
    fun lots(item: String, sort: String = "buyout_price", order: String = "asc", limit: Int = 100): Pair<Int, List<Lot>> {
        val o = get("auction/$item/lots?limit=$limit&sort=$sort&order=$order&additional=true")
        val arr = o.optJSONArray("lots") ?: JSONArray()
        val lots = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val add = l.optJSONObject("additional")
            Lot(
                amount = l.optInt("amount", 1),
                startPrice = l.optLong("startPrice"),
                currentPrice = if (l.has("currentPrice") && !l.isNull("currentPrice")) l.getLong("currentPrice") else null,
                buyoutPrice = l.optLong("buyoutPrice"),
                endTime = Instant.parse(l.getString("endTime")),
                level = add?.takeIf { it.has("ptn") }?.optInt("ptn"),
            )
        }
        return o.optInt("total", lots.size) to lots
    }

    fun history(item: String, limit: Int = 100): List<Sale> {
        val arr = get("auction/$item/history?limit=$limit").optJSONArray("prices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Sale(s.optInt("amount", 1), s.optLong("price"), Instant.parse(s.getString("time")))
        }
    }
}

/** База предметов из официального репозитория EXBO, кэшируется на неделю. */
class Items(private val context: Context) {
    private val cache = mutableMapOf<String, List<Item>>()

    fun load(realm: String): List<Item> = cache.getOrPut(realm) {
        val file = File(context.filesDir, "listing_$realm.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L
        val text = if (fresh) {
            file.readText()
        } else {
            runCatching { http("$DB/$realm/listing.json").also { file.writeText(it) } }
                .getOrElse { if (file.exists()) file.readText() else throw it }
        }
        parse(realm, text)
    }

    private fun parse(realm: String, text: String): List<Item> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val data = o.getString("data")
            val lines = o.getJSONObject("name").optJSONObject("lines")
            Item(
                id = data.substringAfterLast('/').removeSuffix(".json"),
                name = lines?.optString("ru")?.ifBlank { null } ?: lines?.optString("en").orEmpty(),
                icon = "$DB/$realm${o.getString("icon")}",
                color = o.optString("color", "DEFAULT"),
                category = data.removePrefix("/items/").substringBefore('/'),
            )
        }.sortedBy { it.name }
    }
}

val CATEGORIES = linkedMapOf(
    "" to "Все",
    "weapon" to "Оружие",
    "armor" to "Броня",
    "artefact" to "Артефакты",
    "attachment" to "Обвесы",
    "bullet" to "Патроны",
    "supply" to "Снабжение",
    "containers" to "Контейнеры",
    "backpacks" to "Рюкзаки",
    "grenade" to "Гранаты",
    "misc" to "Разное",
    "other" to "Прочее",
)
