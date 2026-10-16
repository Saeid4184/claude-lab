package ir.sobhanbeton.fleet.data

/** How a vehicle's usage is measured: road trucks by odometer, pumps/loaders by engine hours.
 *  Drives every later calculation (fuel consumption per 100 km vs per hour, service intervals). */
enum class MeterType(val displayName: String, val unit: String) {
    KM("کیلومتر", "کیلومتر"),
    HOURS("ساعت کارکرد", "ساعت");

    companion object {
        fun fromStringOrDefault(value: String?, fallback: MeterType = KM): MeterType =
            runCatching { valueOf(value ?: fallback.name) }.getOrDefault(fallback)
    }
}

/**
 * The operational sub-fleets. Carried over from the security app's MachineryCategory (same
 * keyword classification of the roster's group labels, e.g. "میکسر - آمیکو"), plus LOADER,
 * which the machinery department also looks after. [defaultMeterType] is only the suggestion
 * for a new vehicle — it can be changed per vehicle.
 */
enum class VehicleCategory(val displayName: String, val defaultMeterType: MeterType) {
    MIXER("میکسرها", MeterType.KM),
    CONCRETE_PUMP("پمپ‌های بتن", MeterType.HOURS),
    DUMP_TRUCK("کمپرسی و تریلی", MeterType.KM),
    LOADER("لودرها", MeterType.HOURS),
    LOGISTICS("تدارکات و سبک", MeterType.KM);

    companion object {
        fun fromStringOrDefault(value: String?): VehicleCategory =
            runCatching { valueOf(value ?: LOGISTICS.name) }.getOrDefault(LOGISTICS)

        /** Buckets a free-text group label into a category. Anything unrecognized falls back to
         *  LOGISTICS as the general-purpose catch-all. */
        fun classify(group: String?): VehicleCategory {
            val g = group.orEmpty()
            return when {
                g.contains("میکسر") -> MIXER
                g.contains("پمپ") -> CONCRETE_PUMP
                g.contains("لودر") -> LOADER
                g.contains("کمپرسی") || g.contains("کمپرسور") || g.contains("تریلر") ||
                    g.contains("تریلی") || g.contains("کامیون") -> DUMP_TRUCK
                else -> LOGISTICS
            }
        }
    }
}

enum class VehicleStatus(val displayName: String) {
    ACTIVE("فعال"),
    IN_REPAIR("در تعمیر"),
    OUT_OF_SERVICE("خارج از سرویس");

    companion object {
        fun fromStringOrDefault(value: String?): VehicleStatus =
            runCatching { valueOf(value ?: ACTIVE.name) }.getOrDefault(ACTIVE)
    }
}

enum class DriverStatus(val displayName: String) {
    ACTIVE("فعال"),
    SUSPENDED("تعلیق"),
    LEFT("قطع همکاری");

    companion object {
        fun fromStringOrDefault(value: String?): DriverStatus =
            runCatching { valueOf(value ?: ACTIVE.name) }.getOrDefault(ACTIVE)
    }
}
