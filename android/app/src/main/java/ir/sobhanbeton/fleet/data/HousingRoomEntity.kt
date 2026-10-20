package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * A room in the drivers' accommodation ("محل اسکان رانندگان"). Who lives in it is stored on the
 * driver ([DriverEntity.roomId]) — a driver is in at most one room.
 */
@Entity(tableName = "housing_rooms")
data class HousingRoomEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    /** Beds; null = not specified. */
    val capacity: Int? = null,
    val notes: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
