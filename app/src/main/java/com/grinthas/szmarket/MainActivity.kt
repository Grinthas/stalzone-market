package com.grinthas.szmarket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Палитра в стиле stalcraft.trade: тёмно-синий фон, карточки с тонкой рамкой.
private val Bg = Color(0xFF11111C)
private val CardBg = Color(0xFF141423)
private val Border = Color(0xFF2E3350)
private val Muted = Color(0xFF8E93A6)
private val Accent = Color(0xFFE8622A)
private val Good = Color(0xFF7FD98A)

fun rarityColor(color: String): Color = when (color) {
    "RANK_NEWBIE" -> Color(0xFF8FDB8A)
    "RANK_STALKER" -> Color(0xFF6FA8FF)
    "RANK_VETERAN" -> Color(0xFFB38CFF)
    "RANK_MASTER" -> Color(0xFFF08A9A)
    "RANK_LEGEND", "QUEST_ITEM" -> Color(0xFFE8C14A)
    else -> Color(0xFFD5D8E0)
}

fun qualityColor(q: Int): Color = when (q) {
    0 -> Color(0xFFD5D8E0)
    1 -> Color(0xFF8FDB8A)
    2 -> Color(0xFF6FA8FF)
    3 -> Color(0xFFB38CFF)
    4 -> Color(0xFFF08A9A)
    else -> Color(0xFFE8C14A)
}

fun money(v: Long): String =
    v.toString().reversed().chunked(3).joinToString(" ").reversed() + "₽"

fun timeLeft(end: Instant): String {
    val d = Duration.between(Instant.now(), end)
    if (d.isNegative || d.isZero) return "время истекло"
    val h = d.toHours()
    return when {
        h >= 24 -> "${h / 24} д ${h % 24} ч"
        h > 0 -> "$h ч ${d.toMinutes() % 60} мин"
        else -> "${d.toMinutes().coerceAtLeast(1)} мин"
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

private enum class Tab(val title: String) { FAVORITES("Избранное"), SEARCH("Поиск"), SETTINGS("Настройки") }

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var api: Api
    private lateinit var itemDb: Items

    // Счётчик изменений настроек: заставляет экраны перезагрузить данные.
    private var version by mutableIntStateOf(0)
    private var favorites by mutableStateOf<Map<String, Long>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(this)
        api = Api(store)
        itemDb = Items(this)
        favorites = store.favorites
        AlertWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }

    private fun setFavorite(id: String, threshold: Long?) {
        favorites = favorites.toMutableMap().apply { if (threshold == null) remove(id) else put(id, threshold) }
        store.favorites = favorites
    }

    @Composable
    private fun App() {
        var tab by remember { mutableStateOf(Tab.FAVORITES) }
        var opened by remember { mutableStateOf<Item?>(null) }
        val all by produceState<List<Item>?>(null, version) {
            value = withContext(Dispatchers.IO) { runCatching { itemDb.load(store.region.realm) }.getOrNull() }
        }
        BackHandler(opened != null) { opened = null }

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header()
            Box(Modifier.weight(1f)) {
                val item = opened
                when {
                    item != null -> DetailScreen(item)
                    tab == Tab.FAVORITES -> FavoritesScreen(all) { opened = it }
                    tab == Tab.SEARCH -> SearchScreen(all) { opened = it }
                    else -> SettingsScreen()
                }
            }
            Row(Modifier.fillMaxWidth().background(CardBg).padding(vertical = 4.dp)) {
                Tab.entries.forEach { t ->
                    TextButton(
                        onClick = { tab = t; opened = null },
                        modifier = Modifier.weight(1f),
                    ) { Text(t.title, color = if (tab == t && opened == null) Accent else Muted) }
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF181B2B)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).background(Accent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("SZ", fontWeight = FontWeight.Bold, color = Color.White) }
            Spacer(Modifier.width(12.dp))
            Text("Stalzone Market", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.weight(1f))
            RegionPicker()
        }
    }

    @Composable
    private fun RegionPicker() {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, border = BorderStroke(1.dp, Border)) {
                Text(store.region.id.uppercase() + " ▾", color = Color.White)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Region.entries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.title) }, onClick = {
                        store.region = r
                        open = false
                        version++
                    })
                }
            }
        }
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        Surface(
            color = CardBg,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Border),
            modifier = modifier.fillMaxWidth(),
        ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
    }

    @Composable
    private fun ItemTitle(item: Item, size: Int = 44) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = item.icon, contentDescription = null, modifier = Modifier.size(size.dp))
            Spacer(Modifier.width(12.dp))
            Text(item.name, color = rarityColor(item.color), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Muted, modifier = Modifier.weight(1f))
            Text(value, color = valueColor)
        }
    }

    @Composable
    private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, color = Muted)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }

    // ---------- Избранное ----------

    @Composable
    private fun FavoritesScreen(all: List<Item>?, open: (Item) -> Unit) {
        val byId = remember(all) { all.orEmpty().associateBy { it.id } }
        val list = favorites.keys.mapNotNull { byId[it] }
        when {
            all == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Message("Избранное пусто. Найдите предмет во вкладке «Поиск» и нажмите ☆.")
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!store.hasKeys) item { KeysBanner() }
                items(list, key = { it.id }) { FavoriteCard(it) { open(it) } }
            }
        }
    }

    @Composable
    private fun KeysBanner() {
        Card { Text("Укажите ключи API во вкладке «Настройки», чтобы видеть цены.", color = Color(0xFFF0C27A)) }
    }

    private data class Summary(val total: Int, val minBuyout: Long?, val avgSale: Long?, val lots: List<Lot>)

    private fun summary(id: String): Summary {
        val (total, lots) = api.lots(id, limit = 20)
        val sales = runCatching { api.history(id, 20) }.getOrDefault(emptyList())
        val min = lots.filter { it.buyoutPrice > 0 }.minOfOrNull { it.buyoutPrice / it.amount.coerceAtLeast(1) }
        val avg = sales.takeIf { it.isNotEmpty() }?.map { it.price / it.amount.coerceAtLeast(1) }?.average()?.toLong()
        return Summary(total, min, avg, lots)
    }

    @Composable
    private fun FavoriteCard(item: Item, open: () -> Unit) {
        val state by produceState<Result<Summary>?>(null, item.id, version) {
            value = if (store.hasKeys) withContext(Dispatchers.IO) { runCatching { summary(item.id) } } else null
        }
        val threshold = favorites[item.id] ?: 0
        Card(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = open)) {
            ItemTitle(item)
            Spacer(Modifier.height(4.dp))
            val s = state
            when {
                s == null && store.hasKeys -> InfoRow("Загрузка…", "")
                s == null -> InfoRow("Цены", "нет ключей API")
                s.isFailure -> Text(s.exceptionOrNull()?.message ?: "Ошибка", color = Color(0xFFE57373))
                else -> {
                    val v = s.getOrThrow()
                    val min = v.minBuyout
                    InfoRow("Лотов на аукционе", v.total.toString())
                    InfoRow(
                        "Мин. выкуп за шт.",
                        min?.let(::money) ?: "—",
                        if (min != null && threshold > 0 && min <= threshold) Good else Color.White,
                    )
                    InfoRow("Ср. цена продажи", v.avgSale?.let(::money) ?: "—")
                }
            }
            if (threshold > 0) InfoRow("Уведомить при цене", "≤ ${money(threshold)}", Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Подробнее", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    // ---------- Поиск ----------

    @Composable
    private fun SearchScreen(all: List<Item>?, open: (Item) -> Unit) {
        var query by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("") }
        if (all == null) {
            Message("Не удалось загрузить базу предметов. Проверьте интернет.", "Повторить") { version++ }
            return
        }
        val found = remember(all, query, category) {
            val q = query.trim().lowercase()
            all.asSequence()
                .filter { category.isEmpty() || it.category == category }
                .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                .take(200)
                .toList()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Название предмета") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATEGORIES.forEach { (id, title) ->
                    FilterChip(selected = category == id, onClick = { category = id }, label = { Text(title) })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(found, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open(item) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { ItemTitle(item, 40) }
                        val fav = item.id in favorites
                        TextButton(onClick = { setFavorite(item.id, if (fav) null else 0) }) {
                            Text(if (fav) "★" else "☆", fontSize = 22.sp, color = if (fav) Color(0xFFE8C14A) else Muted)
                        }
                    }
                }
            }
        }
    }

    // ---------- Предмет ----------

    private val sorts = listOf(
        Triple("Дешевле выкуп", "buyout_price", "asc"),
        Triple("Дороже выкуп", "buyout_price", "desc"),
        Triple("Скоро закончатся", "time_left", "asc"),
        Triple("Новые", "time_created", "desc"),
    )

    @Composable
    private fun DetailScreen(item: Item) {
        var sort by remember { mutableIntStateOf(0) }
        var reload by remember { mutableIntStateOf(0) }
        // null — все качества; иначе индекс в QUALITIES. Только для артефактов.
        var quality by remember(item.id) { mutableStateOf<Int?>(null) }
        val isArtefact = item.category == "artefact"
        val lots by produceState<Result<Pair<Int, List<Lot>>>?>(null, item.id, sort, version, reload) {
            value = null
            value = withContext(Dispatchers.IO) {
                runCatching { api.lots(item.id, sorts[sort].second, sorts[sort].third) }
            }
        }
        val allSales by produceState<List<Sale>?>(null, item.id, version, reload) {
            value = withContext(Dispatchers.IO) { runCatching { api.history(item.id) }.getOrNull() }
        }
        val sales = allSales?.filter { quality == null || it.quality == quality }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card {
                    ItemTitle(item, 56)
                    FavoriteControls(item)
                }
            }
            if (!store.hasKeys) item { KeysBanner() }
            if (isArtefact) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = quality == null, onClick = { quality = null }, label = { Text("Все") })
                        QUALITIES.forEachIndexed { i, name ->
                            FilterChip(
                                selected = quality == i,
                                onClick = { quality = i },
                                label = { Text(name, color = qualityColor(i)) },
                            )
                        }
                    }
                }
            }
            sales?.takeIf { it.size >= 2 }?.let { list ->
                item {
                    Card {
                        Text("Цена продажи за шт.", color = Color.White, fontWeight = FontWeight.SemiBold)
                        PriceChart(list)
                        val unit = list.map { it.price / it.amount.coerceAtLeast(1) }
                        InfoRow("Мин / сред / макс", "${money(unit.min())} / ${money(unit.average().toLong())} / ${money(unit.max())}")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Лоты", fontSize = 20.sp, color = Color.White, modifier = Modifier.weight(1f))
                    SortPicker(sort) { sort = it }
                    TextButton(onClick = { reload++ }) { Text("⟳", fontSize = 20.sp) }
                }
            }
            val l = lots
            when {
                l == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                l.isFailure -> item { Message(l.exceptionOrNull()?.message ?: "Ошибка", "Повторить") { reload++ } }
                l.getOrThrow().second.isEmpty() -> item { Message("Сейчас нет лотов на аукционе.") }
                else -> {
                    val (total, all) = l.getOrThrow()
                    val list = all.filter { quality == null || it.quality == quality }
                    item {
                        Text(
                            if (quality == null) "Всего лотов: $total"
                            else "${QUALITIES[quality!!]}: ${list.size} из ${all.size} загруженных",
                            color = Muted,
                        )
                    }
                    if (list.isEmpty()) item { Message("Нет лотов такого качества.") }
                    items(list) { LotCard(item, it) }
                }
            }
            sales?.takeIf { it.isNotEmpty() }?.let { list ->
                item { Text("Последние продажи", fontSize = 20.sp, color = Color.White) }
                item {
                    Card {
                        list.take(30).forEach { s ->
                            val q = s.quality?.let { " · ${QUALITIES.getOrElse(it) { "?" }}" }.orEmpty()
                            val lvl = s.level?.takeIf { it > 0 }?.let { " +$it" }.orEmpty()
                            InfoRow("${dateFmt.format(s.time)} · ${s.amount} шт.$q$lvl", money(s.price))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FavoriteControls(item: Item) {
        val fav = item.id in favorites
        var text by remember(item.id) { mutableStateOf(favorites[item.id]?.takeIf { it > 0 }?.toString().orEmpty()) }
        if (!fav) {
            Button(onClick = { setFavorite(item.id, 0) }) { Text("☆ В избранное") }
            return
        }
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter(Char::isDigit).take(12) },
            label = { Text("Уведомить, если выкуп за шт. ≤") },
            suffix = { Text("₽") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { setFavorite(item.id, text.toLongOrNull() ?: 0) }) { Text("Сохранить") }
            OutlinedButton(onClick = { setFavorite(item.id, null) }) { Text("★ Убрать") }
        }
    }

    @Composable
    private fun SortPicker(selected: Int, onSelect: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, border = BorderStroke(1.dp, Border)) {
                Text(sorts[selected].first + " ▾", color = Color.White, fontSize = 13.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                sorts.forEachIndexed { i, s ->
                    DropdownMenuItem(text = { Text(s.first) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }

    @Composable
    private fun LotCard(item: Item, lot: Lot) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = item.icon, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                val suffix = buildString {
                    lot.level?.takeIf { it > 0 }?.let { append(" +$it") }
                    if (lot.amount > 1) append(" ×${lot.amount}")
                }
                Column {
                    Text(item.name + suffix, color = rarityColor(item.color), fontSize = 17.sp)
                    lot.quality?.let { q ->
                        Text(QUALITIES.getOrElse(q) { "Качество $q" }, color = qualityColor(q), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            InfoRow("Время", timeLeft(lot.endTime))
            InfoRow("Цена ставки", money(lot.currentPrice ?: lot.startPrice))
            InfoRow("Цена выкупа", if (lot.buyoutPrice > 0) money(lot.buyoutPrice) else "—")
            if (lot.amount > 1 && lot.buyoutPrice > 0) {
                InfoRow("Выкуп за шт.", money(lot.buyoutPrice / lot.amount), Muted)
            }
        }
    }

    @Composable
    private fun PriceChart(sales: List<Sale>) {
        val points = sales.sortedBy { it.time }.map { it.price.toFloat() / it.amount.coerceAtLeast(1) }
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val min = points.min()
            val max = points.max()
            val span = (max - min).takeIf { it > 0 } ?: 1f
            val step = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = i * step
                val y = size.height - (p - min) / span * (size.height - 8f) - 4f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (k in 0..2) {
                val y = size.height * k / 2f
                drawLine(Border, Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
        }
    }

    // ---------- Настройки ----------

    @Composable
    private fun SettingsScreen() {
        var id by remember { mutableStateOf(store.clientId) }
        var secret by remember { mutableStateOf(store.clientSecret) }
        var url by remember { mutableStateOf(store.apiUrl) }
        var status by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }

        LaunchedEffect(checking) {
            if (!checking) return@LaunchedEffect
            status = withContext(Dispatchers.IO) {
                runCatching { store.token(); "Ключи работают ✓" }.getOrElse { it.message ?: "Ошибка" }
            }
            checking = false
            version++
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Text("Ключи API EXBO", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Цены берутся из официального API Stalzone. Зарегистрируйте приложение в EXBO, " +
                        "после одобрения скопируйте сюда client_id и client_secret. Ключи хранятся только на телефоне.",
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedTextField(id, { id = it }, label = { Text("client_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    secret, { secret = it }, label = { Text("client_secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            store.clientId = id
                            store.clientSecret = secret
                            status = null
                            checking = true
                        },
                        enabled = !checking,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text(if (checking) "Проверяю…" else "Сохранить и проверить") }
                    OutlinedButton(onClick = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://eapi.stalzone.com/docs")))
                    }) { Text("Как получить") }
                }
                status?.let { Text(it, color = if (it.endsWith("✓")) Good else Color(0xFFE57373)) }
            }
            Card {
                Text("Адрес API", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Менять, только если EXBO сменит домен.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { store.apiUrl = url; version++ }) { Text("Сохранить") }
                    OutlinedButton(onClick = { url = DEFAULT_API; store.apiUrl = DEFAULT_API; version++ }) { Text("По умолчанию") }
                }
            }
            Text(
                "Уведомления о ценах проверяются в фоне примерно раз в 15 минут. " +
                    "Названия и иконки — из открытой базы EXBO-Studio/stalzone-database.",
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}
