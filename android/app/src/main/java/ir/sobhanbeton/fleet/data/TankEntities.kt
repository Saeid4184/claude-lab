package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * The factory's diesel tank (one twin tank, treated as a single stock). A single row with id
 * [MAIN_ID]: capacity and the level below which the app warns.
 */
@Entity(tableName = "tank_config")
data class TankConfigEntity(
    @PrimaryKey
    @DocumentId
    val id: String = MAIN_ID,
    val capacityLiters: Double = DEFAULT_CAPACITY,
    val lowLevelLiters: Double = DEFAULT_LOW_LEVEL,
    val updatedAt: Long = 0L
) {
    companion object {
        const val MAIN_ID = "main"
        const val DEFAULT_CAPACITY = 12_000.0
        const val DEFAULT_LOW_LEVEL = 2_000.0
    }
}

/** A tanker delivery into the tank. No money fields. */
@Entity(tableName = "tank_deliveries", indices = [Index("date")])
data class TankDeliveryEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val date: Long = 0L,
    val liters: Double = 0.0,
    /** Waybill / delivery note number. */
    val reference: String? = null,
    val supplier: String? = null,
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
)

/** Fuel taken from the tank for something that isn't a fleet vehicle (generator, batching
 *  plant…). Fuel given to fleet vehicles is already recorded as fuel logs with source
 *  INTERNAL_TANK and is never entered twice. */
@Entity(tableName = "tank_withdrawals", indices = [Index("date")])
data class TankWithdrawalEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val date: Long = 0L,
    val liters: Double = 0.0,
    val consumer: String = "",
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
)

/** A physical measurement of what's actually in the tank (dipstick / gauge). Each one resets
 *  the running stock and shows the discrepancy against what the records predicted. */
@Entity(tableName = "tank_readings", indices = [Index("date")])
data class TankReadingEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val date: Long = 0L,
    val liters: Double = 0.0,
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
)
