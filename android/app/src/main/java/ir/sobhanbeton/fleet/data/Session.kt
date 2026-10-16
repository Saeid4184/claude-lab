package ir.sobhanbeton.fleet.data

/**
 * In-memory holder for the currently signed-in user's profile, populated once at sign-in/app
 * start (see AuthRepository.restoreSessionIfSignedIn) and read synchronously everywhere the UI
 * needs to know "who is this" or "may they edit" without an extra async Firestore round-trip.
 */
object Session {
    @Volatile
    var currentUser: UserProfile? = null
        private set

    fun set(profile: UserProfile?) {
        currentUser = profile
    }

    fun clear() {
        currentUser = null
    }

    fun isSignedIn(): Boolean = currentUser != null

    /** Only the expert records/edits data; the manager's account is view-and-report only. */
    fun canEdit(): Boolean = currentUser?.role == UserRole.EXPERT

    fun isApproved(): Boolean = currentUser?.role.let { it == UserRole.EXPERT || it == UserRole.MANAGER }

    /** Falls back to a neutral label if, somehow, a write happens before the session is loaded. */
    fun currentUserOrGuestLabel(): String = currentUser?.name?.takeIf { it.isNotBlank() } ?: "کاربر نامشخص"
}
