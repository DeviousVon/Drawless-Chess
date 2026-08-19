import Foundation

enum ReleaseChannel {
    /// Game Review remains a private-beta surface until its documented exit gates pass.
    /// App Store Release builds intentionally contain only the finished core game. The compiled
    /// marker is persisted locally and archive inspection verifies it in the executable.
#if DRAWLESS_IOS_GAME_REVIEW
    static let gameReviewEnabled = true
    static let marker = "private-review"
#else
    static let gameReviewEnabled = false
    static let marker = "app-store-core"
#endif

    static let markerPreferenceKey = "releaseChannel.marker"
}

enum CoreReleaseCheckpointMigration {
    static func apply(
        defaults: UserDefaults,
        activeKey: String,
        completedReviewKey: String
    ) {
        guard let completedReview = defaults.string(forKey: completedReviewKey) else { return }
        if defaults.string(forKey: activeKey) == completedReview {
            // Debug may already have promoted the terminal payload to the active slot before the
            // first core-only Release launch. Remove only that exact duplicate so a different live
            // game checkpoint survives the channel change.
            defaults.removeObject(forKey: activeKey)
        }
        defaults.removeObject(forKey: completedReviewKey)
    }
}
