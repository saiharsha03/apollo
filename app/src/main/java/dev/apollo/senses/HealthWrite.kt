package dev.apollo.senses

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import dev.apollo.actions.ActionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** His food and weight logs, checked before they touch his health record. Pure. */
object HealthWriteRules {
    fun meal(word: String?): Int = when (word?.lowercase()?.trim()) {
        "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
        "lunch" -> MealType.MEAL_TYPE_LUNCH
        "dinner" -> MealType.MEAL_TYPE_DINNER
        "snack" -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }

    /** Kilograms from kg or lb; null when missing or not a person's weight. */
    fun kg(kg: Double?, lb: Double?): Double? = (kg ?: lb?.let { it * 0.45359237 })?.takeIf { it in 20.0..350.0 }

    /** A meal's energy, null when missing or not a meal. */
    fun kcal(v: Double?): Double? = v?.takeIf { it in 0.0..10_000.0 }

    /** Grams of one nutrient; absent or impossible values are left out. */
    fun grams(v: Double?): Double? = v?.takeIf { it in 0.0..2_000.0 }
}

/**
 * phone_do `log_food` {name, kcal, protein_g, carbs_g, fat_g, meal, at} and `log_weight` {kg or lb, at}: they write his
 * health record, so they are tap actions (approvals table: unknown actions are tap). Zeus's Food and Weight logs.
 */
object HealthWrite {
    val WRITE: Set<String> = setOf(HealthPermission.getWritePermission(WeightRecord::class), HealthPermission.getWritePermission(NutritionRecord::class))

    fun register() {
        ActionRegistry.handlers["log_food"] = { c, a -> food(c, a) }
        ActionRegistry.handlers["log_weight"] = { c, a -> weight(c, a) }
    }

    private fun at(a: JSONObject): Instant = a.optLong("at", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: Instant.now()
    private fun num(a: JSONObject, k: String): Double? = if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { !it.isNaN() } else null

    private fun write(c: Context, need: String, record: androidx.health.connect.client.records.Record, done: String): Pair<Boolean, String> = runBlocking(Dispatchers.IO) {
        if (!Health.available(c)) return@runBlocking false to "Health Connect isn't on this phone"
        val hc = HealthConnectClient.getOrCreate(c)
        if (need !in hc.permissionController.getGrantedPermissions()) return@runBlocking false to "not allowed: Health Connect > Apollo > allow writing it"
        hc.insertRecords(listOf(record))
        true to done
    }

    fun food(c: Context, a: JSONObject): Pair<Boolean, String> {
        val kcal = HealthWriteRules.kcal(num(a, "kcal")) ?: return false to "log_food needs kcal (0 to 10000)"
        val t = at(a)
        val end = t.plusSeconds(120)
        val zone = ZoneId.systemDefault().rules
        val name = a.optString("name").take(100).ifBlank { null }
        val r = NutritionRecord(
            startTime = t, startZoneOffset = zone.getOffset(t), endTime = end, endZoneOffset = zone.getOffset(end),
            metadata = Metadata.manualEntry(), name = name, energy = Energy.kilocalories(kcal),
            protein = HealthWriteRules.grams(num(a, "protein_g"))?.let(Mass::grams),
            totalCarbohydrate = HealthWriteRules.grams(num(a, "carbs_g"))?.let(Mass::grams),
            totalFat = HealthWriteRules.grams(num(a, "fat_g"))?.let(Mass::grams),
            mealType = HealthWriteRules.meal(a.optString("meal")),
        )
        return write(c, HealthPermission.getWritePermission(NutritionRecord::class), r, "logged ${name ?: "a meal"}, ${Math.round(kcal)} kcal")
    }

    fun weight(c: Context, a: JSONObject): Pair<Boolean, String> {
        val kg = HealthWriteRules.kg(num(a, "kg"), num(a, "lb")) ?: return false to "log_weight needs kg or lb (a person's weight)"
        val t = at(a)
        val r = WeightRecord(t, ZoneId.systemDefault().rules.getOffset(t), Mass.kilograms(kg), Metadata.manualEntry())
        return write(c, HealthPermission.getWritePermission(WeightRecord::class), r, "logged ${Math.round(kg * 10) / 10.0} kg")
    }
}
