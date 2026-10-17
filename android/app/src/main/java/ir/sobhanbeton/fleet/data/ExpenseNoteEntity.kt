package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * The expert's private "یادداشت هزینه‌ها": a date, what it was for, an amount (Rial). By design
 * it is linked to nothing else and appears nowhere else — no vehicle, form, list, report or
 * Excel export reads it. firestore.rules let only the EXPERT read or write the collection, and
 * [CloudSync] only mirrors it onto the expert's phone.
 */
@Entity(tableName = "expense_notes", indices = [Index("date")])
data class ExpenseNoteEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val date: Long = 0L,
    val title: String = "",
    val amount: Long = 0L,
    val notes: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
