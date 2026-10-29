package dev.apollo.senses

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.apollo.clio.Clio
import dev.apollo.ui.ApolloTheme
import dev.apollo.ui.Type
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Exercise types Athena reads by name; anything else is "other". Pure. */
object HealthRules {
    fun kind(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "cricket"
        else -> "other"
    }

    /** Readings to one number per day (HRV, blood oxygen): the mean, to one decimal. */
    fun dailyMean(readings: List<Pair<String, Double>>): Map<String, Double> =
        readings.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> Math.round(v.average() * 10) / 10.0 }
}

/**
 * Health Connect, read only (Samsung Health syncs the smartwatch into it). The last 7 days as `health_day` facts
 * (Health Connect's own totals, so two apps counting the same steps aren't added twice; weight, the nutrition he logs,
 * heart-rate variability and blood oxygen as day means) and each exercise session as a `health_walk` fact. Writes are
 * only his food and weight logs, after his tap ([HealthWrite]). A day goes again only when its numbers changed. Runs every 3 hours and when Apollo comes to the
 * front (Health Connect lets an app read in the background only with that extra permission).
 */
object Health {
    val READ: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class), HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class), HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class), HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class), HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class), HealthPermission.getReadPermission(NutritionRecord::class),
    )

    fun available(c: Context) = runCatching { HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun granted(c: Context): Set<String> =
        if (!available(c)) emptySet() else runCatching { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("health.read", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HealthWorker>(3, TimeUnit.HOURS).build())
    }

    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("health.now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<HealthWorker>().build())
    }

    suspend fun read(c: Context) {
        if (!available(c)) return
        val hc = HealthConnectClient.getOrCreate(c)
        val have = hc.permissionController.getGrantedPermissions()
        fun has(r: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) = HealthPermission.getReadPermission(r) in have
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(6)
        val p = c.getSharedPreferences("senses_health", Context.MODE_PRIVATE)
        // A missing permission would fail the whole request, so only the metrics he allowed.
        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(HeartRateRecord::class)) add(HeartRateRecord.BPM_AVG)
            if (has(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_MIN)
            if (has(SleepSessionRecord::class)) add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
            if (has(ExerciseSessionRecord::class)) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            if (has(WeightRecord::class)) add(WeightRecord.WEIGHT_AVG)
            if (has(NutritionRecord::class)) addAll(listOf(NutritionRecord.ENERGY_TOTAL, NutritionRecord.PROTEIN_TOTAL, NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL, NutritionRecord.TOTAL_FAT_TOTAL))
        }
        val byDay = linkedMapOf<String, JSONObject>()
        fun day(date: String) = byDay.getOrPut(date) { JSONObject().put("date", date) }
        if (metrics.isNotEmpty()) {
            val days = hc.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, TimeRangeFilter.between(from.atStartOfDay(), today.plusDays(1).atStartOfDay()), Period.ofDays(1)))
            for (d in days) {
                val r = d.result
                val day = day(d.startTime.toLocalDate().toString())
                r[StepsRecord.COUNT_TOTAL]?.let { day.put("steps", it) }
                r[DistanceRecord.DISTANCE_TOTAL]?.let { day.put("distance_m", Math.round(it.inMeters)) }
                r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { day.put("active_kcal", Math.round(it.inKilocalories)) }
                r[HeartRateRecord.BPM_AVG]?.let { day.put("avg_hr", it) }
                r[RestingHeartRateRecord.BPM_MIN]?.let { day.put("resting_hr", it) }
                r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { day.put("sleep_min", it.toMinutes()) }
                r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { day.put("exercise_min", it.toMinutes()) }
                r[WeightRecord.WEIGHT_AVG]?.let { day.put("weight_kg", Math.round(it.inKilograms * 10) / 10.0) }
                r[NutritionRecord.ENERGY_TOTAL]?.let { day.put("food_kcal", Math.round(it.inKilocalories)) }
                r[NutritionRecord.PROTEIN_TOTAL]?.let { day.put("protein_g", Math.round(it.inGrams)) }
                r[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.let { day.put("carbs_g", Math.round(it.inGrams)) }
                r[NutritionRecord.TOTAL_FAT_TOTAL]?.let { day.put("fat_g", Math.round(it.inGrams)) }
            }
        }
        // No totals for these in Health Connect: every reading of the week, as a mean per day.
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), Instant.now())
        fun date(t: Instant) = t.atZone(zone).toLocalDate().toString()
        if (has(HeartRateVariabilityRmssdRecord::class)) runCatching {
            HealthRules.dailyMean(all(hc, HeartRateVariabilityRmssdRecord::class, range).map { date(it.time) to it.heartRateVariabilityMillis })
                .forEach { (d, v) -> day(d).put("hrv_ms", v) }
        }
        if (has(OxygenSaturationRecord::class)) runCatching {
            HealthRules.dailyMean(all(hc, OxygenSaturationRecord::class, range).map { date(it.time) to it.percentage.value })
                .forEach { (d, v) -> day(d).put("spo2_pct", v) }
        }
        for (day in byDay.values) {
            if (day.length() <= 1) continue
            val k = "day." + day.getString("date")
            if (p.getString(k, null) == day.toString()) continue
            Senses.sink.post("health_day", day, false)
            p.edit().putString(k, day.toString()).apply()
        }
        if (has(ExerciseSessionRecord::class)) {
            val start = from.atStartOfDay(zone).toInstant()
            val sent = p.getStringSet("walks", emptySet()).orEmpty()
            val seen = mutableSetOf<String>()
            val sessions = hc.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, Instant.now()))).records
            for (s in sessions) {
                val id = s.metadata.id
                seen += id
                if (id in sent) continue
                Senses.sink.post("health_walk", JSONObject().put("id", id).put("start", s.startTime.toEpochMilli()).put("end", s.endTime.toEpochMilli())
                    .put("kind", HealthRules.kind(s.exerciseType)).put("title", s.title ?: "").put("app", s.metadata.dataOrigin.packageName), false)
            }
            p.edit().putStringSet("walks", seen).apply()
        }
        // Days that fell out of the window are forgotten.
        p.edit().apply { p.all.keys.filter { it.startsWith("day.") && it.removePrefix("day.") < from.toString() }.forEach { remove(it) } }.apply()
    }
}

/** Every page Health Connect has for the range, not only the first one. */
private suspend fun <T : androidx.health.connect.client.records.Record> all(hc: HealthConnectClient, type: kotlin.reflect.KClass<T>, range: TimeRangeFilter): List<T> {
    val out = ArrayList<T>()
    var token: String? = null
    do {
        val r = hc.readRecords(ReadRecordsRequest(type, range, pageToken = token))
        out += r.records
        token = r.pageToken
    } while (token != null)
    return out
}

class HealthWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        runCatching { Health.read(applicationContext) }.onFailure {
            // Background reads without the extra permission land here; the next foreground run catches up.
            Clio.log(applicationContext, "health", "read failed", JSONObject().put("error", it.javaClass.simpleName))
        }
        return Result.success()
    }
}

/** Health Connect's "why does Apollo read this" page. */
class HealthWhyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApolloTheme {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Why Apollo reads your health data", style = Type.title)
                    Text("Steps, distance, active calories, sleep, heart rate, heart-rate variability, blood oxygen, weight, nutrition and workouts are read " +
                        "from Health Connect and sent to Athena, your assistant, so she can keep your health notes and notice changes. Apollo writes only the " +
                        "meals and weights you log, each after your tap, and never shares health data with anyone else.",
                        style = Type.body, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
