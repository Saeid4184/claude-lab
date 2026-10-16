package ir.sobhanbeton.fleet.data

/** EXPERT (کارشناس ماشین‌آلات) records and edits everything. MANAGER (مدیر) sees everything
 *  and pulls reports but never edits data. PENDING is every newly-registered account until
 *  the expert approves it from Settings → کاربران — so an APK that ends up on a stranger's
 *  phone can't read the fleet's data just by signing up. */
enum class UserRole(val displayName: String) {
    EXPERT("کارشناس ماشین‌آلات"),
    MANAGER("مدیر"),
    PENDING("در انتظار تأیید");

    companion object {
        fun fromStringOrDefault(value: String?): UserRole =
            runCatching { valueOf(value ?: PENDING.name) }.getOrDefault(PENDING)
    }
}
