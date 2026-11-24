package app.wattlo

import app.wattlo.core.R
import android.app.job.JobScheduler
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Where the schedule comes from. Slot states: 0 = power on, 1 = off, 2 = switching (may be off).
 * [host] is what the lists show, [url] is the site or repository the user can open (the sources are credited
 * in the app, and bezsvitla.com.ua asks for an active link).
 */
enum class Source(val host: String, val url: String) {
    POE("poe.pl.ua", "https://www.poe.pl.ua/disconnection/power-outages/"),
    /** The data service of the project: the schedules of every region collected from the sources below (see [JsonSources.wattlo]). */
    WATTLO("wattlo.poltorak.family", "https://wattlo.poltorak.family/"),
    BEZSVITLA("bezsvitla.com.ua", "https://bezsvitla.com.ua/"),
    /** The JSON of the YASNO web app (Kyiv, Dnipro): the schedules of the operators themselves. */
    YASNO("yasno.ua", "https://yasno.ua/"),
    /** JSON files collected by volunteers from the sites of the operators and kept on GitHub (see [RAW_BASE]). */
    DTEK_DATA("outage-data-ua (GitHub)", "https://github.com/Baskerville42/outage-data-ua"),
    OE_DATA("OE_OUTAGE_DATA (GitHub)", "https://github.com/yaroslav2901/OE_OUTAGE_DATA"),
    VOE_DATA("gpv-voe-vinnytsia (GitHub)", "https://github.com/vn-progr/gpv-voe-vinnytsia");

    /**
     * Dates a [load] call asks the source about. A date that is requested but missing from the result
     * has no (or no longer has a) published schedule. [hour] is the current hour in [Schedule.ZONE].
     * POE needs a separate request per day, so tomorrow can be skipped with [withTomorrow]
     * (the other source returns both days in one response).
     */
    fun requested(today: LocalDate, hour: Int, withTomorrow: Boolean = true): List<LocalDate> =
        // POE publishes tomorrow's schedule in the evening, so it is not requested before noon
        if (this == POE && (hour < 12 || !withTomorrow)) listOf(today) else listOf(today, today.plusDays(1))

    fun load(
        region: Region, queue: String, today: LocalDate, hour: Int = java.time.LocalTime.now(Schedule.ZONE).hour,
        withTomorrow: Boolean = true,
    ): Map<LocalDate, IntArray> = when (this) {
        POE -> {
            // newgpv-info.php returns an HTML table of all queues for the date; empty means not published yet
            val out = LinkedHashMap<LocalDate, IntArray>()
            for (d in requested(today, hour, withTomorrow)) {
                val body = "seldate=" + java.net.URLEncoder.encode(
                    """{"date_in":"${d.format(POE_DATE)}"}""", "UTF-8"
                )
                val html = Http.request("https://www.poe.pl.ua/customs/newgpv-info.php", body)
                Schedule.parsePoe(html, queue)?.let { out[d] = it }
            }
            out
        }
        BEZSVITLA -> Schedule.parseBezsvitla(Http.request("https://bezsvitla.com.ua/${region.slug}"), today, queue)
            .mapValues { bits(it.value) }
        WATTLO -> JsonSources.wattlo(Http.request("https://wattlo.poltorak.family/v1/regions/${region.slug}.json"), queue, host)
        YASNO -> {
            val (regionId, dsoId) = region.yasno ?: throw FetchError(R.string.err_schedule_not_found, queue)
            JsonSources.yasno(Http.request("https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/$regionId/dsos/$dsoId/planned-outages"), queue)
        }
        DTEK_DATA, OE_DATA, VOE_DATA -> {
            val file = region.dataFile ?: throw FetchError(R.string.err_schedule_not_found, queue)
            JsonSources.mirror(Http.request(RAW_BASE.getValue(this) + file), queue, host)
        }
    }

    private companion object {
        /** Where the JSON files of each repository are (raw files of the main branch). */
        val RAW_BASE = mapOf(
            DTEK_DATA to "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/",
            OE_DATA to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/",
            VOE_DATA to "https://raw.githubusercontent.com/vn-progr/gpv-voe-vinnytsia/main/data/",
        )
        val POE_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        fun bits(f: BooleanArray) = IntArray(f.size) { if (f[it]) 1 else 0 }
    }
}

/**
 * The regions with a published schedule: the page of the region on bezsvitla.com.ua is [slug]; [queues] are the
 * groups the region's operator uses (Kyiv has 60 sub-queues, everywhere else 12); [sources] are the sites that
 * have its schedule, the first one is the default (and the order in which they are tried when one fails).
 * Poltava also has the official schedule of its own power company (poe.pl.ua). The JSON sources come first only
 * where they matched bezsvitla.com.ua when they were compared (YASNO, the DTEK files); the files that volunteers
 * keep for the other power companies sometimes lag behind the sites by hours, so there bezsvitla.com.ua is first.
 */
enum class Region(
    val slug: String, @androidx.annotation.StringRes val nameRes: Int, val queues: List<String>, val sources: List<Source>,
    /** The YASNO region and operator numbers (see [Source.YASNO]). */
    val yasno: Pair<Int, Int>? = null,
    /** The name of the region's file in the repositories of the JSON sources. */
    val dataFile: String? = null,
) {
    POLTAVA("poltavska-oblast", R.string.region_poltava, TWELVE, listOf(Source.WATTLO, Source.POE, Source.BEZSVITLA)),
    KHARKIV("kharkivska-oblast", R.string.region_kharkiv, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Kharkivoblenerho.json"),
    DNIPRO("dnipropetrovska-oblast", R.string.region_dnipro, TWELVE, listOf(Source.WATTLO, Source.YASNO, Source.DTEK_DATA, Source.BEZSVITLA), yasno = 3 to 301, dataFile = "dnipro.json"),
    ODESA("odeska-oblast", R.string.region_odesa, TWELVE, listOf(Source.WATTLO, Source.DTEK_DATA, Source.BEZSVITLA), dataFile = "odesa.json"),
    KYIV_OBLAST("kyivska-oblast", R.string.region_kyiv_oblast, TWELVE, listOf(Source.WATTLO, Source.DTEK_DATA, Source.BEZSVITLA), dataFile = "kyiv-region.json"),
    CHERKASY("cherkaska-oblast", R.string.region_cherkasy, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Cherkasyoblenergo.json"),
    CHERNIHIV("chernihivska-oblast", R.string.region_chernihiv, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Chernihivoblenergo.json"),
    IVANO_FRANKIVSK("ivano-frankivska-oblast", R.string.region_ivano_frankivsk, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Prykarpattiaoblenerho.json"),
    KHMELNYTSKYI("khmelnytska-oblast", R.string.region_khmelnytskyi, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Khmelnytskoblenerho.json"),
    KIROVOHRAD("kirovohradska-oblast", R.string.region_kirovohrad, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA)),
    LVIV("lvivska-oblast", R.string.region_lviv, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Lvivoblenerho.json"),
    MYKOLAIV("mykolaivska-oblast", R.string.region_mykolaiv, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA)),
    RIVNE("rivnenska-oblast", R.string.region_rivne, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Rivneoblenergo.json"),
    SUMY("sumska-oblast", R.string.region_sumy, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA)),
    TERNOPIL("ternopilska-oblast", R.string.region_ternopil, TERNOPIL_QUEUES, listOf(Source.WATTLO, Source.BEZSVITLA)),
    VINNYTSIA("vinnytska-oblast", R.string.region_vinnytsia, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.VOE_DATA), dataFile = "Vinnytsiaoblenerho.json"),
    VOLYN("volynska-oblast", R.string.region_volyn, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA)),
    ZAKARPATTIA("zakarpatska-oblast", R.string.region_zakarpattia, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Zakarpattiaoblenerho.json"),
    ZAPORIZHZHIA("zaporizka-oblast", R.string.region_zaporizhzhia, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Zaporizhzhiaoblenergo.json"),
    ZHYTOMYR("zhytomyrska-oblast", R.string.region_zhytomyr, TWELVE, listOf(Source.WATTLO, Source.BEZSVITLA, Source.OE_DATA), dataFile = "Zhytomyroblenergo.json"),
    // Chernivtsi: the site calls them groups ("Група 1" .. "Група 12"), here they are queues "1" .. "12"
    CHERNIVTSI("chernivetska-oblast", R.string.region_chernivtsi, (1..12).map { "$it" }, listOf(Source.WATTLO, Source.BEZSVITLA)),
    KYIV("kyiv", R.string.region_kyiv, (1..60).map { "$it.1" }, listOf(Source.WATTLO, Source.YASNO, Source.DTEK_DATA, Source.BEZSVITLA), yasno = 25 to 902, dataFile = "kyiv.json");

    /** The queue chosen when the region is picked: the first one of the region. */
    val defaultQueue: String get() = queues.first()

    companion object {
        /** Every queue name of every region: their caches share a naming scheme. */
        val ALL_QUEUES: List<String> get() = entries.flatMap { it.queues }.distinct()
        fun of(name: String?): Region = entries.firstOrNull { it.name == name } ?: POLTAVA

        /** All regions in the alphabetical order of their names in the language of [c] (for the lists to choose from). */
        fun sorted(c: android.content.Context): List<Region> {
            val collator = java.text.Collator.getInstance(c.resources.configuration.locales[0])
            return entries.sortedWith { a, b -> collator.compare(c.getString(a.nameRes), c.getString(b.nameRes)) }
        }
    }
}

// the 12 groups of most operators: 1.1, 1.2, 2.1 ... 6.2
private val TWELVE = (1..6).flatMap { q -> (1..2).map { "$q.$it" } }

// Ternopil region: 1.1 ... 6.2 plus 7.2 and 12.2, as the site lists them
private val TERNOPIL_QUEUES = listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2", "7.2", "12.2")

/** A fetch failure with a localized message: resolved with a Context where the error is stored. */
class FetchError(@androidx.annotation.StringRes val res: Int, vararg val args: Any) : Exception()

object Http {
    /** What the requests inside a [sharing] block have already got; null outside of one. */
    private val shared = ThreadLocal<MutableMap<String, String>?>()

    /** The real request, replaced in tests. */
    internal var transport: (String, String?) -> String = ::network

    /**
     * Inside [block] the same request (address and form) goes to the network once: a refresh asks for the main queue
     * and for every extra queue, but they all live on the same page or file of the region.
     */
    fun <T> sharing(block: () -> T): T {
        shared.set(mutableMapOf())
        try { return block() } finally { shared.remove() }
    }

    /** GET, or a form-urlencoded POST when [form] is given. */
    fun request(url: String, form: String? = null): String {
        val cache = shared.get() ?: return transport(url, form)
        val key = url + "\n" + (form ?: "")
        cache[key]?.let { return it }
        return transport(url, form).also { cache[key] = it }
    }

    private fun network(url: String, form: String?): String {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Wattlo/1.0 (Wear OS and Android app; +https://github.com/termit-uanic/wattlo)")
            conn.setRequestProperty("Accept-Language", "uk")
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 403 || code == 503) throw FetchError(R.string.err_blocked, code)
            if (code != 200) error("HTTP $code")
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if ("Just a moment" in html) throw FetchError(R.string.err_browser_check)
            return html
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * An extra queue (phone app only) is a queue of any region: its id is "REGION:queue", for example "LVIV:1.1". The id is
 * also the key of its cache and of the name the user gave it. The main queue has no id (it is the one in the settings).
 */
object Extra {
    /** At most this many: every region the extra queues live in costs a request per refresh. */
    const val MAX = 8

    fun id(region: Region, queue: String) = "${region.name}:$queue"

    fun region(id: String): Region? = id.substringBefore(':', "").let { n -> Region.entries.firstOrNull { it.name == n } }

    fun queue(id: String): String = id.substringAfter(':', id)

    fun isValid(id: String): Boolean = region(id)?.let { queue(id) in it.queues } == true

    /** "Poltava" for "Poltava region", "Київ" for "Київ": the name of the region without the kind of region. */
    fun shortRegion(c: android.content.Context, region: Region): String =
        c.getString(region.nameRes).removeSuffix(" область").removeSuffix(" region").removeSuffix(" city")

    /** "Queue 3.2", or "Queue 3.2 · Lviv" when the queue is in another region than the main queue. */
    fun title(c: android.content.Context, id: String): String {
        val r = region(id)
        val q = c.getString(R.string.queue_name, queue(id))
        return if (r == null || r == Settings.region(c)) q else q + " · " + shortRegion(c, r)
    }

    /** The queue of the id as a person says it: "3.2" in the main queue's region, "Lviv 3.2" in another one. */
    fun text(c: android.content.Context, id: String): String {
        val r = region(id) ?: return id
        return if (r == Settings.region(c)) queue(id) else shortRegion(c, r) + " " + queue(id)
    }
}

/** Settings: source + queue, display and notification options. */
object Settings {
    /** Set by the watch app: its display options are the ones the phone sets for the watch (see [Synced]). */
    @Volatile var isWatch = false

    /** The 12 groups of the official poe.pl.ua table (and of most regions); a region can have its own, see [Region]. */
    val QUEUES = Region.POLTAVA.queues

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Poltava region until the user picks another (the setup wizard asks first thing). */
    fun region(c: Context): Region = Region.of(p(c).getString("region", null))

    /** The queues of the chosen region. */
    fun queues(c: Context): List<String> = region(c).queues

    /** The stored source if the region has it, else the region's own (a source that is gone, like energy-ua.info, too). */
    fun source(c: Context): Source {
        val r = region(c)
        val stored = runCatching { Source.valueOf(p(c).getString("source", null)!!) }.getOrNull()
        return stored?.takeIf { it in r.sources } ?: r.sources.first()
    }

    /** The queue until the user picks one (the setup wizard asks first thing). */
    const val DEFAULT_QUEUE = "1.1"

    fun queue(c: Context): String = p(c).getString("queue", null)?.takeIf { it in queues(c) } ?: region(c).defaultQueue

    /**
     * The user picked this queue (the setup wizard): store it and share it with the other device even when it is
     * the queue that was in force anyway, so that both devices end up with an explicit choice.
     */
    fun chooseQueue(c: Context, queue: String) {
        if (queue != queue(c)) set(c, queue = queue)
        else { p(c).edit().putString("queue", queue).apply(); touch(c) }
    }

    /** Whether "switching" (yellow) slots count as no power in the countdown (tile and complication). Defaults to true. */
    fun maybeIsOff(c: Context): Boolean = p(c).getBoolean("maybeOff", true)

    fun setMaybeIsOff(c: Context, v: Boolean) { p(c).edit().putBoolean("maybeOff", v).apply(); touch(c) }

    /** How many minutes before power goes off / on to notify (0 = don't notify). */
    fun notifyOff(c: Context): Int = p(c).getInt("notifyOff", 0)
    fun notifyOn(c: Context): Int = p(c).getInt("notifyOn", 0)
    fun setNotify(c: Context, off: Int = notifyOff(c), on: Int = notifyOn(c)) {
        p(c).edit().putInt("notifyOff", off).putInt("notifyOn", on).apply()
        touch(c)
    }

    /**
     * Quiet hours: from this hour (0..23) to that hour, exclusive, possibly across midnight. Notifications
     * that arrive then are posted silently. Equal hours mean off, which is the default.
     */
    fun quietFrom(c: Context): Int = p(c).getInt("quietFrom", 0)
    fun quietTo(c: Context): Int = p(c).getInt("quietTo", 0)
    fun setQuiet(c: Context, from: Int, to: Int) { p(c).edit().putInt("quietFrom", from).putInt("quietTo", to).apply(); touch(c) }

    /** How the day is drawn on the tile: 1 = one ring of 24 hours, 2 = two rings of 12 hours. */
    fun tileRings(c: Context): Int = if (p(c).getInt("tileRings", 1) == 2) 2 else 1
    fun setTileRings(c: Context, rings: Int) { p(c).edit().putInt("tileRings", if (rings == 2) 2 else 1).apply(); touch(c) }

    /** With two rings on the tile: also label the hours 12-24 inside the inner ring (default), or only 0-12 outside. */
    fun tileInnerLabels(c: Context): Boolean = p(c).getBoolean("tileInnerLabels", true)
    fun setTileInnerLabels(c: Context, v: Boolean) { p(c).edit().putBoolean("tileInnerLabels", v).apply(); touch(c) }

    /** Show "then 2h without power" under the time of the next change on the tile. On by default. */
    fun tileShowNext(c: Context): Boolean = p(c).getBoolean("tileShowNext", true)
    fun setTileShowNext(c: Context, v: Boolean) { p(c).edit().putBoolean("tileShowNext", v).apply(); touch(c) }

    /** The same two settings for the day dials inside the app (kept apart from the tile's). */
    /** The number of rings until the user picks one: the watch starts with 1, the phone app sets 2 at start. */
    @Volatile var appRingsDefault = 1
    fun appRings(c: Context): Int = if (p(c).getInt("appRings", appRingsDefault) == 2) 2 else 1
    fun setAppRings(c: Context, rings: Int) { p(c).edit().putInt("appRings", if (rings == 2) 2 else 1).apply(); if (isWatch) touch(c) }
    fun appInnerLabels(c: Context): Boolean = p(c).getBoolean("appInnerLabels", true)
    fun setAppInnerLabels(c: Context, v: Boolean) { p(c).edit().putBoolean("appInnerLabels", v).apply(); if (isWatch) touch(c) }

    /**
     * The app screens of the watch as the phone sets them. On the watch they are its own [appRings] and
     * [appInnerLabels]; on the phone, which has its own app settings, they are separate copies.
     */
    fun wearAppRings(c: Context): Int =
        if (isWatch) appRings(c) else if (p(c).getInt("wearAppRings", 1) == 2) 2 else 1
    fun setWearAppRings(c: Context, rings: Int) {
        if (isWatch) return setAppRings(c, rings)
        p(c).edit().putInt("wearAppRings", if (rings == 2) 2 else 1).apply(); touch(c)
    }
    /** How the app screens draw the schedule (see [AppView]); the phone and the watch each have their own. */
    fun appView(c: Context): AppView =
        AppView.of(p(c).getString("appView", null), if (isWatch) AppView.WATCH else AppView.PHONE)
    fun setAppView(c: Context, v: AppView) { p(c).edit().putString("appView", v.name).apply(); if (isWatch) touch(c) }
    fun wearAppView(c: Context): AppView =
        if (isWatch) appView(c) else AppView.of(p(c).getString("wearAppView", null), AppView.WATCH)
    fun setWearAppView(c: Context, v: AppView) {
        if (isWatch) return setAppView(c, v)
        p(c).edit().putString("wearAppView", v.name).apply(); touch(c)
    }

    fun wearAppInnerLabels(c: Context): Boolean =
        if (isWatch) appInnerLabels(c) else p(c).getBoolean("wearAppInnerLabels", true)
    fun setWearAppInnerLabels(c: Context, v: Boolean) {
        if (isWatch) return setAppInnerLabels(c, v)
        p(c).edit().putBoolean("wearAppInnerLabels", v).apply(); touch(c)
    }

    /**
     * The watch tells the phone its display options once, so the phone's settings screen starts with the watch's
     * real values instead of the defaults (and the phone's first change does not reset them).
     */
    fun publishOnce(c: Context) {
        if (p(c).getBoolean("published", false)) return
        p(c).edit().putBoolean("published", true).apply()
        touch(c)
    }

    /** Notify when the schedule for today changed. Off by default. */
    fun notifyTodayChanged(c: Context): Boolean = p(c).getBoolean("notifyTodayChanged", false)
    fun setNotifyTodayChanged(c: Context, v: Boolean) { p(c).edit().putBoolean("notifyTodayChanged", v).apply(); touch(c) }

    /** Notify when the schedule for tomorrow appeared. Off by default. */
    fun notifyTomorrowNew(c: Context): Boolean = p(c).getBoolean("notifyTomorrowNew", false)
    fun setNotifyTomorrowNew(c: Context, v: Boolean) { p(c).edit().putBoolean("notifyTomorrowNew", v).apply(); touch(c) }

    /** Push messages about changed schedules: on by default; shared with the other device. */
    fun pushEnabled(c: Context): Boolean = p(c).getBoolean("push", true)
    fun setPushEnabled(c: Context, v: Boolean) { p(c).edit().putBoolean("push", v).apply(); touch(c) }

    /** Changing the source/queue clears the cache: the old schedule no longer applies. */
    fun set(c: Context, source: Source = source(c), queue: String = queue(c)) {
        val r = region(c)
        val src = source.takeIf { it in r.sources } ?: r.sources.first()
        val q = queue.takeIf { it in r.queues } ?: r.defaultQueue
        if (src == source(c) && q == queue(c)) return
        val sourceChanged = src != source(c)
        p(c).edit().putString("source", src.name).putString("queue", q).apply()
        // the new main queue is no longer an extra one
        val id = Extra.id(r, q)
        if (id in extraQueues(c)) writeExtras(c, extraQueues(c) - id)
        if (sourceChanged) Store.clearAll(c) else { Store.clear(c); Store.clear(c, id) }
        touch(c)
    }

    /**
     * Another region: its operator has its own queues and its own sites, so the source goes to the region's
     * default, the queue to the first of the region, and the main queue's cache does not apply. The extra queues
     * (they have regions of their own) stay, except the one that is now the main queue.
     */
    fun setRegion(c: Context, region: Region) {
        if (region == region(c)) return
        val keep = extraQueues(c).filter { it != Extra.id(region, region.defaultQueue) }
        p(c).edit().putString("region", region.name).putString("source", region.sources.first().name)
            .putString("queue", region.defaultQueue).apply()
        writeExtras(c, keep)
        Store.clearAll(c)
        touch(c)
    }

    /**
     * Extra queues, for the phone app only: it can show their state next to the main queue [queue]. They are not
     * synchronised, and the watch, the widgets and the reminders only ever use the main queue. Ids, see [Extra];
     * a stored value that is not one (an older version kept plain queue names) is ignored.
     */
    fun extraQueues(c: Context): List<String> {
        val main = Extra.id(region(c), queue(c))
        return (p(c).getString("extraQueues", "") ?: "").split(",")
            .filter { Extra.isValid(it) && it != main }.distinct().take(Extra.MAX)
    }

    fun setExtraQueues(c: Context, list: Collection<String>) {
        val old = extraQueues(c)
        val main = Extra.id(region(c), queue(c))
        val new = list.filter { Extra.isValid(it) && it != main }.distinct().take(Extra.MAX)
        writeExtras(c, new)
        Push.sync(c)
        (old - new.toSet()).forEach { Store.clear(c, it) } // no cache for a queue that is no longer shown
    }

    const val MAX_LABEL = 16

    /**
     * A short name of a queue ("parents", "sister", "home"), for the phone app only; "" when none. The name belongs to the
     * queue (region and number), so it stays with it when the queue moves between "main" and "additional".
     */
    fun queueLabel(c: Context, id: String): String = p(c).getString("label_$id", "").orEmpty()

    /** The name of the main queue. */
    fun mainLabel(c: Context): String = queueLabel(c, Extra.id(region(c), queue(c)))

    /** The name of a queue as the screens address it: null is the main queue. */
    fun labelOf(c: Context, extra: String?): String = if (extra == null) mainLabel(c) else queueLabel(c, extra)

    fun setQueueLabel(c: Context, id: String, label: String) {
        if (!Extra.isValid(id)) return
        val clean = label.trim().take(MAX_LABEL)
        p(c).edit().apply { if (clean.isEmpty()) remove("label_$id") else putString("label_$id", clean) }.apply()
        // the name of the main queue is shared with the watch (the names of the additional queues stay on the phone)
        if (id == Extra.id(region(c), queue(c))) touch(c)
    }

    /** The name of the main queue shown after the queue: "5.2 (home)", or just "5.2". */
    fun mainQueueText(c: Context): String = queue(c) + mainLabel(c).let { if (it.isEmpty()) "" else " ($it)" }

    private fun writeExtras(c: Context, list: List<String>) {
        p(c).edit().putString("extraQueues", list.joinToString(",")).apply()
    }

    /**
     * The settings shared between the watch and the phone (synchronised over the Wear OS Data Layer, see
     * [SettingsSync]); the display options of the tile and of the app screens stay per device.
     */
    data class Synced(
        val source: String, val queue: String, val maybeOff: Boolean,
        val notifyOff: Int, val notifyOn: Int, val quietFrom: Int, val quietTo: Int,
        val notifyToday: Boolean, val notifyTomorrow: Boolean,
        // how the watch looks: the tile, and the app screens of the watch
        val tileRings: Int = 1, val tileInnerLabels: Boolean = true, val tileShowNext: Boolean = true,
        val wearAppRings: Int = 1, val wearAppInnerLabels: Boolean = true,
        // a device without a region (an older build) is in the Poltava region
        val region: String = Region.POLTAVA.name,
        // push messages about changed schedules (Google Firebase Cloud Messaging), see Push
        val push: Boolean = true,
        // the name of the main queue ("home"); empty = none
        val mainLabel: String = "",
        // how the watch's app draws the schedule: a name of [AppView]
        val wearAppView: String = AppView.RING.name,
    ) {
        /** Values from the other device are not trusted blindly: anything out of range falls back to the defaults. */
        fun sanitized(): Synced {
            val r = Region.of(region)
            return copy(
                region = r.name,
                source = if (r.sources.any { it.name == source }) source else r.sources.first().name,
                queue = queue.takeIf { it in r.queues } ?: r.defaultQueue,
                mainLabel = mainLabel.trim().take(MAX_LABEL),
                wearAppView = AppView.of(wearAppView, AppView.WATCH).name,
            ).clamped()
        }

        private fun clamped() = copy(
            notifyOff = notifyOff.coerceAtLeast(0),
            notifyOn = notifyOn.coerceAtLeast(0),
            quietFrom = quietFrom.coerceIn(0, 23),
            quietTo = quietTo.coerceIn(0, 23),
            tileRings = if (tileRings == 2) 2 else 1,
            wearAppRings = if (wearAppRings == 2) 2 else 1,
        )
    }

    fun synced(c: Context) = Synced(
        source(c).name, queue(c), maybeIsOff(c), notifyOff(c), notifyOn(c), quietFrom(c), quietTo(c),
        notifyTodayChanged(c), notifyTomorrowNew(c),
        tileRings(c), tileInnerLabels(c), tileShowNext(c), wearAppRings(c), wearAppInnerLabels(c), region(c).name, pushEnabled(c), mainLabel(c),
        wearAppView(c).name,
    )

    /** When the shared settings were last changed on any device (ms since epoch; 0 = never). */
    fun syncTs(c: Context): Long = p(c).getLong("syncTs", 0L)

    /** A shared setting was changed here: stamp it and hand it to the other device. */
    private fun touch(c: Context) {
        p(c).edit().putLong("syncTs", System.currentTimeMillis()).apply()
        SettingsSync.push(c)
        Push.sync(c)
    }

    /**
     * Takes the shared settings of the other device if they are newer (the newest change wins). Writes the
     * preferences directly, not through the setters, so the change is not sent back.
     * Returns what changed: null = nothing was applied, otherwise whether the source or queue changed too.
     */
    fun applySynced(c: Context, v: Synced, ts: Long): Boolean? {
        if (ts <= syncTs(c)) return null
        val v = v.sanitized()
        val before = Triple(region(c), source(c), queue(c))
        p(c).edit()
            .putString("region", v.region).putString("source", v.source).putString("queue", v.queue).putBoolean("maybeOff", v.maybeOff)
            .putInt("notifyOff", v.notifyOff).putInt("notifyOn", v.notifyOn)
            .putInt("quietFrom", v.quietFrom).putInt("quietTo", v.quietTo)
            .putBoolean("notifyTodayChanged", v.notifyToday).putBoolean("notifyTomorrowNew", v.notifyTomorrow)
            .putInt("tileRings", v.tileRings).putBoolean("tileInnerLabels", v.tileInnerLabels)
            .putBoolean("tileShowNext", v.tileShowNext)
            .putInt(if (isWatch) "appRings" else "wearAppRings", v.wearAppRings)
            .putBoolean(if (isWatch) "appInnerLabels" else "wearAppInnerLabels", v.wearAppInnerLabels)
            .putBoolean("push", v.push)
            .putString(if (isWatch) "appView" else "wearAppView", v.wearAppView)
            .putLong("syncTs", ts).apply()
        // The name goes with the main queue. A device that has no name for the queue it switched to sends none:
        // that must not wipe the name this device has for it, so an empty name counts only for the same queue.
        val newId = Extra.id(Region.of(v.region), v.queue)
        if (Extra.isValid(newId) && (v.mainLabel.isNotEmpty() || (before.first.name == v.region && before.third == v.queue))) {
            p(c).edit().apply { if (v.mainLabel.isEmpty()) remove("label_$newId") else putString("label_$newId", v.mainLabel) }.apply()
        }
        Push.sync(c)
        val dataChanged = before != Triple(region(c), source(c), queue(c))
        if (dataChanged) {
            // the old schedule no longer applies; a new main queue is no longer an extra one
            if (before.first != region(c)) {
                // another region: the main queue's cache does not apply; the extra queues stay (they have regions of their own)
                Store.clearAll(c)
            } else {
                if (before.second != source(c)) Store.clearAll(c) else { Store.clear(c); Store.clear(c, Extra.id(region(c), queue(c))) }
            }
            writeExtras(c, extraQueues(c)) // without the one that is the main queue now
            Push.sync(c)
        }
        return dataChanged
    }
}

/**
 * Cache in SharedPreferences: "2026-11-08=0000111...;2026-11-09=..." (48 slots of 30 min).
 *
 * Every function takes an optional [q]: null is the main queue (what the watch, the widgets and the reminders use);
 * an extra queue of the phone app (see [Settings.extraQueues]) has a cache file of its own.
 */
object Store {
    private fun name(q: String?) = if (q == null) "sched" else "sched_$q"
    private fun p(c: Context, q: String?) = c.getSharedPreferences(name(q), Context.MODE_PRIVATE)

    /**
     * Merges new days into the old ones (new ones win), drops days older than yesterday
     * and the days in [missing]: the source no longer publishes them, so the cached copy is stale.
     */
    fun merge(
        old: Map<LocalDate, IntArray>, fresh: Map<LocalDate, IntArray>, today: LocalDate,
        missing: Collection<LocalDate> = emptyList(),
    ): java.util.SortedMap<LocalDate, IntArray> =
        (old.filterKeys { it !in missing } + fresh)
            .filterKeys { !it.isBefore(today.minusDays(1)) }.toSortedMap()

    fun save(c: Context, fresh: Map<LocalDate, IntArray>, missing: Collection<LocalDate> = emptyList(), q: String? = null) {
        val today = LocalDate.now(Schedule.ZONE)
        val merged = merge(raw(c, q), fresh, today, missing)
        val s = merged.entries.joinToString(";") { (d, f) -> "$d=" + f.joinToString("") }
        // fetched here, not by the phone: see fromPhone
        p(c, q).edit().putString("days", s).putLong("fetched", System.currentTimeMillis())
            .remove("error").remove("fromPhone").apply()
    }

    /** The cache as it is stored ("2026-11-08=0000111...;2026-11-09=..."), empty if there is none. */
    fun daysString(c: Context, q: String? = null): String = p(c, q).getString("days", null) ?: ""

    /** Watch: the whole cache from the phone (see [ScheduleSync]), as it was fetched at [fetchedAt]. */
    fun replaceFromPhone(c: Context, days: String, fetchedAt: Long) {
        p(c, null).edit().putString("days", days).putLong("fetched", fetchedAt).remove("error")
            .putBoolean("fromPhone", true).apply()
    }

    /** The cache came from the phone and has not been replaced by the watch's own fetch since. */
    fun fromPhone(c: Context): Boolean = p(c, null).getBoolean("fromPhone", false)

    /** The last parsed cache string and its result: the tile, the complication and the app read it all the time. */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<LocalDate, IntArray>>>()

    /** Parses "2026-11-08=0000111...;2026-11-09=..."; broken entries (bad date, wrong length) are skipped. */
    fun parseDays(s: String): Map<LocalDate, IntArray> = s.split(";").mapNotNull {
        val parts = it.split("=")
        if (parts.size != 2 || parts[1].length != Schedule.SLOTS) return@mapNotNull null
        runCatching { LocalDate.parse(parts[0]) }.getOrNull()
            ?.let { d -> d to IntArray(Schedule.SLOTS) { i -> parts[1][i].digitToIntOrNull() ?: 0 } }
    }.toMap()

    /** Slot states 0/1/2 per day. The result is shared between callers: it must not be modified. */
    fun raw(c: Context, q: String? = null): Map<LocalDate, IntArray> {
        val s = p(c, q).getString("days", null) ?: return emptyMap()
        parsed[name(q)]?.let { if (it.first == s) return it.second }
        return parseDays(s).also { parsed[name(q)] = s to it }
    }

    /**
     * Slot states (0 on, 1 off, 2 yellow) to "no power" flags. A yellow slot is the time needed for switching
     * after an outage ("power may be absent"): it counts as no power when [maybeIsOff], else as power on.
     * So the setting only moves the end of an outage, never its start.
     */
    fun flagsOf(raw: Map<LocalDate, IntArray>, maybeIsOff: Boolean): Map<LocalDate, BooleanArray> =
        raw.mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 1 || (maybeIsOff && a[it] == 2) } }

    /** true: no power (including "may be off" while switching). */
    fun load(c: Context, maybeIsOff: Boolean = true, q: String? = null): Map<LocalDate, BooleanArray> =
        flagsOf(raw(c, q), maybeIsOff)

    /** true: switching slot, power may be off. */
    fun loadMaybe(c: Context, q: String? = null): Map<LocalDate, BooleanArray> =
        raw(c, q).mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 2 } }

    /** What the user sees: schedule + error. The fetch time is deliberately left out. */
    fun snapshot(c: Context, q: String? = null): String = (p(c, q).getString("days", "") ?: "") + "|" + (error(c, q) ?: "")

    fun clear(c: Context, q: String? = null) { p(c, q).edit().clear().apply() }

    /** The source or the region of the main queue changed: its cache does not apply. The extra queues use sources of their own. */
    fun clearAll(c: Context) { clear(c) }

    fun fetchedAt(c: Context, q: String? = null): Long = p(c, q).getLong("fetched", 0L)

    /** When tomorrow's schedule was last asked of the source (see [Fetcher]: it is rechecked at most hourly). */
    fun tomorrowCheckedAt(c: Context, q: String? = null): Long = p(c, q).getLong("tomorrowChecked", 0L)
    fun markTomorrowChecked(c: Context, at: Long, q: String? = null) { p(c, q).edit().putLong("tomorrowChecked", at).apply() }
    /** Data older than [STALE_WARN_MS]: show a warning, the schedule may have changed. */
    fun isStale(c: Context, now: Long = System.currentTimeMillis(), q: String? = null): Boolean =
        fetchedAt(c, q).let { it > 0 && now - it > STALE_WARN_MS }

    const val STALE_WARN_MS = 3 * 60 * 60_000L

    fun error(c: Context, q: String? = null): String? = p(c, q).getString("error", null)
    fun setError(c: Context, msg: String, q: String? = null) { p(c, q).edit().putString("error", msg).apply() }
}

object Fetcher {
    /** Blocking call; true on success. The error is written to Store.error. */
    fun refresh(c: Context, extra: String? = null): Boolean = try { fetch(c, extra) } catch (e: java.io.IOException) {
        Store.setError(c, c.getString(R.string.err_no_connection), extra)
        false
    } catch (e: FetchError) {
        Store.setError(c, c.getString(e.res, *e.args), extra)
        false
    } catch (e: Exception) {
        Store.setError(c, e.message ?: e.toString(), extra)
        false
    }

    /** The extra queues of the phone app (none on the watch). A failure of one does not stop the others. */
    fun refreshExtras(c: Context) {
        for (q in Settings.extraQueues(c)) refresh(c, q)
    }

    /** [extra] = null: the main queue; otherwise one of the extra queues, which only fills its own cache. */
    private fun fetch(c: Context, extra: String?): Boolean {
        if (extra == null) Refresh.noteAttempt(c)
        // an extra queue lives in a region of its own and uses that region's usual order of sources
        val region = extra?.let { Extra.region(it) } ?: Settings.region(c)
        val source = if (extra != null) region.sources.first() else Settings.source(c)
        val queue = if (extra != null) Extra.queue(extra) else Settings.queue(c)
        val today = LocalDate.now(Schedule.ZONE)
        val hour = java.time.LocalTime.now(Schedule.ZONE).hour
        // Once tomorrow's schedule is cached it rarely changes: recheck it hourly instead of on every refresh
        // (for POE that saves a whole extra request). Until it is published, keep asking.
        val now = System.currentTimeMillis()
        val tomorrow = today.plusDays(1)
        val withTomorrow = tomorrow !in Store.raw(c, extra) || now - Store.tomorrowCheckedAt(c, extra) >= TOMORROW_RECHECK_MS
        val days = loadWithFallback(region, source, queue, today, hour, withTomorrow)
        // settings changed while the request was in flight: the result is stale
        if (extra == null && (region != Settings.region(c) || source != Settings.source(c) || queue != Settings.queue(c))) return false
        if (extra != null && extra !in Settings.extraQueues(c)) return false
        // Tomorrow was asked for but is gone (the source withdrew it): don't keep showing the old copy.
        // Today is kept: a missing today is more likely a glitch of the site than a real change.
        val asked = source.requested(today, hour, withTomorrow)
        val missing = asked.filter { it.isAfter(today) && it !in days }
        val before = Store.raw(c, extra)
        Store.save(c, days, missing, extra)
        if (tomorrow in asked) Store.markTomorrowChecked(c, now, extra)
        if (extra == null) ScheduleSync.push(c) // the watch gets the news at once (no-op on the watch itself)
        // reminders and the surfaces follow the main queue only
        if (extra == null) Notify.onScheduleSaved(c, before, Store.raw(c))
        return true
    }

    private const val TOMORROW_RECHECK_MS = 60 * 60_000L

    /**
     * Watch: the phone sent its schedule of the main queue. Saved and followed up as after an own fetch: the
     * reminders and the surfaces are told if the schedule changed, or only the time on the tile.
     */
    fun acceptFromPhone(c: Context, days: String, fetchedAt: Long) {
        val before = Store.snapshot(c)
        val fetchedBefore = Store.fetchedAt(c)
        val rawBefore = Store.raw(c)
        Store.replaceFromPhone(c, days, fetchedAt)
        Notify.onScheduleSaved(c, rawBefore, Store.raw(c))
        if (Store.snapshot(c) != before) notifyAll(c)
        else if (Store.fetchedAt(c) != fetchedBefore) Surfaces.fetched(c)
        Notify.schedule(c)
    }

    /**
     * The chosen source first; if it fails or has nothing for the queue (the volunteers' JSON files may be out of
     * date, a site may be down), the region's other sources in their order. The error of the chosen source is
     * the one the user sees when none of them helps. No connection at all is not worth trying the others.
     */
    private fun loadWithFallback(
        region: Region, source: Source, queue: String, today: LocalDate, hour: Int, withTomorrow: Boolean,
    ): Map<LocalDate, IntArray> {
        var first: Exception? = null
        for (s in listOf(source) + region.sources.filter { it != source }) {
            try {
                val days = s.load(region, queue, today, hour, withTomorrow)
                if (days.isNotEmpty()) return days
                if (first == null) first = FetchError(R.string.err_schedule_not_found, queue)
            } catch (e: java.net.UnknownHostException) {
                throw first ?: e
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        throw first ?: FetchError(R.string.err_schedule_not_found, queue)
    }

    /**
     * Refresh the cache and redraw what shows it. The tile and the complication are redrawn if the schedule or
     * the error changed (POE usually returns the same schedule, no need to wake the watch face for nothing).
     * If only the fetch time changed, just the tile is redrawn: it is the only place that shows the time
     * ("updated HH:MM") and it would otherwise disagree with the app until its next hourly rebuild.
     */
    fun refreshAndNotify(c: Context): Boolean = Http.sharing { refreshAndNotifyShared(c) }

    private fun refreshAndNotifyShared(c: Context): Boolean {
        val before = Store.snapshot(c)
        val fetchedBefore = Store.fetchedAt(c)
        val ok = refresh(c)
        if (Store.snapshot(c) != before) notifyAll(c)
        else if (Store.fetchedAt(c) != fetchedBefore) Surfaces.fetched(c)
        Notify.schedule(c)
        refreshExtras(c)
        return ok
    }

    fun notifyAll(c: Context) {
        // A BroadcastReceiver's own context may not bind to services, which the tile and complication update
        // requests do, so always go through the application context.
        val app = c.applicationContext
        Surfaces.changed(app)
        Notify.schedule(app)
    }
}

class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Push.sync(applicationContext) // heals the subscriptions (a failed one, a new install)
        // the watch: the phone has delivered the schedule not long ago, nothing to ask the internet for;
        // a push says the schedule has just changed, so the phone's older copy does not count then
        if (!inputData.getBoolean("push", false) && ScheduleSync.phoneDataIsFresh(applicationContext)) {
            android.util.Log.i("RefreshWorker", "skipped: the phone delivered the schedule not long ago")
            return Result.success()
        }
        android.util.Log.i("RefreshWorker", "fetching")
        val ok = withContext(Dispatchers.IO) { Fetcher.refreshAndNotify(applicationContext) }
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** The background job also waits for a battery that is not low: refreshes can wait, the watch cannot. */
    private val netBatteryOk = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

    /**
     * How often the periodic job runs (and how far the system may shift it to batch it with other network jobs).
     * The watch spares its battery: every 30 min. The phone can afford the shortest period Android allows, 15 min.
     */
    private val periodMin get() = if (Settings.isWatch) 30L else 15L
    private val flexMin get() = if (Settings.isWatch) 10L else 5L

    /**
     * The tile, the complication, the widgets and the app trigger an extra refresh when the data is older than this.
     * It is longer than the periodic job interval (+ flex), so that they only step in if that job is stuck, and do
     * not add their own network wake-ups on top of it.
     */
    private val staleMs get() = if (Settings.isWatch) 45 * 60_000L else 25 * 60_000L

    /** Nobody retries sooner than this after an attempt: a site that is down must not get a request every minute. */
    private const val MIN_GAP_MS = 5 * 60_000L

    private fun attempts(c: Context) = c.getSharedPreferences("refresh", Context.MODE_PRIVATE)

    /** The fetcher notes every attempt of the main queue, successful or not (see [ifOlder]). */
    fun noteAttempt(c: Context) { attempts(c).edit().putLong("attempt", System.currentTimeMillis()).apply() }

    /**
     * Periodic refresh, see [periodMin]. The countdown is computed on the device itself, so the data only has to
     * be fresh enough to notice a changed schedule.
     */
    fun ensure(c: Context) {
        // the tile, the complication and the app call this on every request: do the work once per process
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            val wm = WorkManager.getInstance(c.applicationContext)
            wm.cancelUniqueWork("periodic") // old 30-minute job
            wm.cancelUniqueWork("periodic15") // old 15-minute job
            // the phone used to share the watch's 30-minute job; it has its own, with the shorter period
            val name = if (Settings.isWatch) "periodic30" else "periodicPhone15"
            if (!Settings.isWatch) wm.cancelUniqueWork("periodic30")
            // KEEP trusts the WorkManager database and never looks at the system job, but after a reboot the
            // JobScheduler job can be gone while the database still says "enqueued" (seen on the watch: no
            // refresh job for 21 h after a reboot; neither KEEP nor UPDATE brings it back). So look at
            // JobScheduler itself: if our periodic job is not there, replace the work, which registers it.
            // Only the periodic job requires "battery not low", this tells it from the one-off refresh job.
            val hasJob = c.getSystemService(JobScheduler::class.java).allPendingJobs.any {
                it.service.className == JOB_SERVICE && it.isRequireBatteryNotLow
            }
            wm.enqueueUniquePeriodicWork(
                name,
                if (hasJob) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                PeriodicWorkRequestBuilder<RefreshWorker>(periodMin, TimeUnit.MINUTES, flexMin, TimeUnit.MINUTES)
                    .setConstraints(netBatteryOk).build()
            )
            ensured = true
        }
        Push.sync(c)
    }

    @Volatile private var ensured = false

    private const val JOB_SERVICE = "androidx.work.impl.background.systemjob.SystemJobService"

    /** One-off refresh now, or after [delaySec] seconds (the watch waits a little for the phone's schedule). */
    fun now(c: Context, delaySec: Long = 0) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net)
                .setInitialDelay(delaySec, TimeUnit.SECONDS).build()
        )
    }

    /** A push said that a schedule changed: refresh now, whatever the phone has delivered, and replace a running one. */
    fun onPush(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "push", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net)
                .setInputData(androidx.work.workDataOf("push" to true)).build()
        )
    }

    /** ensure() + a one-off refresh if the cache is stale. */
    fun ifStale(c: Context) = ifOlder(c, staleMs)

    /**
     * ensure() + a one-off refresh if the data is older than [maxAgeMs] and the last attempt (even a failed one)
     * is at least [MIN_GAP_MS] ago.
     */
    fun ifOlder(c: Context, maxAgeMs: Long) {
        ensure(c)
        ScheduleSync.pull(c) // (watch only) what the phone sent while this app was not running
        if (shouldFetch(System.currentTimeMillis() - Store.fetchedAt(c), System.currentTimeMillis() - attempts(c).getLong("attempt", 0L), maxAgeMs)) now(c)
    }

    /** The data is older than [maxAgeMs] and nobody tried to refresh it during the last [MIN_GAP_MS]. */
    fun shouldFetch(ageMs: Long, sinceAttemptMs: Long, maxAgeMs: Long): Boolean = ageMs > maxAgeMs && sinceAttemptMs >= MIN_GAP_MS

    /**
     * The watch app was opened. It used to fetch whenever the data was older than 2 minutes, which ignored what the phone
     * had just delivered; now it asks the internet only when the data is as stale as the tile would call it (and not
     * tried a moment ago). Push and the periodic job keep the data fresh in between.
     */
    fun shouldFetchOnOpen(c: Context): Boolean {
        ScheduleSync.pull(c) // what the phone sent while this app was not running
        val t = System.currentTimeMillis()
        return shouldFetch(t - Store.fetchedAt(c), t - attempts(c).getLong("attempt", 0L), staleMs)
    }
}
