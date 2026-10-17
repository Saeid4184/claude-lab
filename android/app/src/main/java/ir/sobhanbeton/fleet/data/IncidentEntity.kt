package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * "حوادث و تخلفات": an accident, a traffic fine, an internal driver violation, a commendation,
 * or anything else worth recording against a vehicle and/or driver. Filled in from
 * [IncidentCatalog]'s phrase lists (the security app's report form, reworded for the machinery
 * department), and rendered into [summaryText]. No money fields — a fine's amount, if needed,
 * goes into the expert's private expense notes.
 */
@Entity(
    tableName = "incidents",
    indices = [Index("vehicleId"), Index("driverId"), Index("occurredAt")]
)
data class IncidentEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val type: String = IncidentType.ACCIDENT.name,
    val occurredAt: Long = 0L,
    val vehicleId: String? = null,
    val vehiclePlate: String? = null,
    val driverId: String? = null,
    val driverName: String? = null,
    val subject: String = "",
    val severity: String? = null,
    val location: String? = null,
    val actionTaken: String? = null,
    val description: String? = null,
    val summaryText: String = "",
    val performedByName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun typeEnum(): IncidentType = IncidentType.fromStringOrDefault(type)
}

enum class IncidentType(val displayName: String) {
    ACCIDENT("تصادف / حادثه"),
    FINE("جریمهٔ رانندگی"),
    VIOLATION("تخلف داخلی راننده"),
    COMMENDATION("تقدیر"),
    OTHER("سایر");

    companion object {
        fun fromStringOrDefault(value: String?): IncidentType =
            runCatching { valueOf(value ?: OTHER.name) }.getOrDefault(OTHER)
    }
}

/** Phrase lists and the one-sentence summary — same "pick, don't type" approach as the
 *  security app's report form, with «سایر» revealing free text. */
object IncidentCatalog {

    const val OTHER = "سایر (تایپ کنید)"

    fun subjectsFor(type: IncidentType): List<String> = when (type) {
        IncidentType.ACCIDENT -> listOf(
            "تصادف با خودرو", "برخورد با مانع یا سازه", "واژگونی", "برخورد در محوطهٔ کارخانه",
            "حادثه حین تخلیهٔ بتن", "آتش‌سوزی خودرو", "آسیب به شخص", OTHER
        )
        IncidentType.FINE -> listOf(
            "سرعت غیرمجاز", "اضافه‌بار", "عبور از چراغ قرمز", "توقف ممنوع", "نداشتن مدارک",
            "ریزش بار یا بتن در معبر", "تردد در ساعات ممنوعه", OTHER
        )
        IncidentType.VIOLATION -> listOf(
            "غیبت یا تأخیر", "عدم رعایت ایمنی", "استفادهٔ غیرمجاز از ماشین", "سوخت‌گیری غیرمجاز",
            "تخلیهٔ بتن خارج از محل", "عدم گزارش خرابی", "بی‌احتیاطی در رانندگی", OTHER
        )
        IncidentType.COMMENDATION -> listOf(
            "نگهداری عالی از ماشین", "کار اضافه در شرایط اضطراری", "رانندگی بدون حادثه",
            "صرفه‌جویی در سوخت", "گزارش به‌موقع خرابی", OTHER
        )
        IncidentType.OTHER -> listOf("اطلاع‌رسانی", "پیگیری", OTHER)
    }

    val severityOptions = listOf("بدون خسارت", "خسارت جزئی", "خسارت سنگین", "جرحی")

    fun showsSeverity(type: IncidentType): Boolean = type == IncidentType.ACCIDENT

    val actionOptions = listOf("تذکر شفاهی", "تذکر کتبی", "کسر از کارانه", "گزارش به مدیریت", "تعلیق موقت", OTHER)

    fun showsAction(type: IncidentType): Boolean = type == IncidentType.VIOLATION || type == IncidentType.FINE

    fun buildSummary(
        type: IncidentType,
        dateText: String,
        subject: String,
        plate: String?,
        driverName: String?,
        severity: String?,
        location: String?,
        actionTaken: String?
    ): String = buildString {
        append("در تاریخ $dateText")
        if (!plate.isNullOrBlank()) append("، ماشین $plate")
        if (!driverName.isNullOrBlank()) append(if (plate.isNullOrBlank()) "، راننده «$driverName»" else " (راننده «$driverName»)")
        val what = subject.ifBlank { "—" }
        when (type) {
            IncidentType.ACCIDENT -> {
                append(" دچار «$what» شد")
                if (!location.isNullOrBlank()) append(" در «$location»")
                if (!severity.isNullOrBlank()) append("؛ نتیجه: $severity")
                append(".")
            }
            IncidentType.FINE -> {
                append(" به دلیل «$what» جریمه شد")
                if (!location.isNullOrBlank()) append(" (محل: $location)")
                append(".")
            }
            IncidentType.VIOLATION -> append(" تخلف «$what» ثبت شد.")
            IncidentType.COMMENDATION -> append(" به دلیل «$what» مورد تقدیر قرار گرفت.")
            IncidentType.OTHER -> append(" موضوع «$what» ثبت شد.")
        }
        if (!actionTaken.isNullOrBlank()) append(" اقدام: $actionTaken.")
    }
}
