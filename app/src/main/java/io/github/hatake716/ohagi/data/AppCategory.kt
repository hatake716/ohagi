package io.github.hatake716.ohagi.data

import android.content.pm.ApplicationInfo
import java.util.Locale

/** iOS App Library 風の自動分類に使う、ランチャー内共通カテゴリー。 */
enum class AppCategory {
    SOCIAL,
    PRODUCTIVITY_FINANCE,
    PHOTO_VIDEO,
    ENTERTAINMENT,
    GAMES,
    NEWS_READING,
    TRAVEL_WEATHER,
    SHOPPING_FOOD,
    HEALTH_FITNESS,
    UTILITIES,
    OTHER,
}

/**
 * Android が公開する ApplicationInfo.category を軸に、Android に存在しない
 * ファイナンス／買い物／健康などの分類だけラベルとパッケージ名で補完する。
 * 端末やストアが category を提供しないアプリも、必ず OTHER までフォールバックする。
 */
internal fun categorizeApp(
    packageName: String,
    label: String,
    androidCategory: Int,
): AppCategory {
    val searchable = "$packageName $label".lowercase(Locale.ROOT)
    val normalizedLabel = label.trim().lowercase(Locale.ROOT)

    fun containsAny(vararg terms: String): Boolean = terms.any(searchable::contains)

    // ゲームは Android の明示分類を最優先する。タイトルに "home" 等を含むゲームを
    // ユーティリティへ誤分類しないため、補助ルールより先に判定する。
    if (androidCategory == ApplicationInfo.CATEGORY_GAME || containsAny(
            ".game", "games", "pokemon", "nintendo", "yostar", "mahjong",
            "ゲーム", "麻雀",
        )
    ) {
        return AppCategory.GAMES
    }

    // Android の標準カテゴリーにない、利用者が探すときに意味のある分類を補完する。
    if (containsAny(
            "openai", "anthropic", "perplexity", "chatgpt", "claude", "gemini",
            "notion", "obsidian", "github", "dropbox", "office", "document",
            "spreadsheet", "calendar", "keep", "drive", "docs", "仕事", "メモ",
        )
    ) {
        return AppCategory.PRODUCTIVITY_FINANCE
    }
    if (containsAny(
            "bank", "wallet", "finance", "trading", "stock", "securit", "credit",
            "paypay", "rakuten.pay", "keitai.payment", "dcard", "cardapp", "ispeed",
            "sbisec", "money", "coin", "証券", "銀行", "決済", "ウォレット",
            "カード", "ペイ", "家計", "投資", "株",
        )
    ) {
        return AppCategory.PRODUCTIVITY_FINANCE
    }
    if (containsAny(
            "shopping", ".shop", "amazon", "aliexpress", "mercari", "yodobashi",
            "nitori", "muji", "rebates", "store", "mall", "food", "restaurant",
            "burger", "mcd", "cafe", "delivery", "coopdeli", "katsuya",
            "買い物", "ショッピング", "フード", "レストラン", "デリバリ", "ニトリ",
        )
    ) {
        return AppCategory.SHOPPING_FOOD
    }
    if (containsAny(
            "health", "fitness", "fitbit", "workout", "walking", "medical", "clinic",
            "ヘルス", "健康", "フィットネス", "歩数", "医療",
        )
    ) {
        return AppCategory.HEALTH_FITNESS
    }
    if (containsAny(
            "weather", "tenki", "forecast", "transit", "train", "taxi", "ubercab",
            "travel", "airline", "flight", "乗換", "天気", "タクシー", "旅行",
        )
    ) {
        return AppCategory.TRAVEL_WEATHER
    }
    if (packageName.contains("naver.line", ignoreCase = true) || normalizedLabel == "line") {
        return AppCategory.SOCIAL
    }

    return when (androidCategory) {
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_VIDEO,
            -> AppCategory.ENTERTAINMENT

        ApplicationInfo.CATEGORY_IMAGE -> AppCategory.PHOTO_VIDEO
        ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
        ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS_READING
        ApplicationInfo.CATEGORY_MAPS -> AppCategory.TRAVEL_WEATHER
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY_FINANCE
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppCategory.UTILITIES
        else -> when {
            containsAny(
                "camera", "photo", "gallery", "image", "canva", "カメラ", "フォト", "写真",
            ) -> AppCategory.PHOTO_VIDEO

            containsAny(
                "music", "video", "youtube", "netflix", "twitch", "primevideo", "spotify",
                "player", "movie", "anime", "音楽", "動画", "テレビ", "映画", "アニメ",
            ) -> AppCategory.ENTERTAINMENT

            containsAny(
                "news", "journal", "reader", "book", "kindle", "magazine",
                "ニュース", "新聞", "書籍", "読書", "マンガ",
            ) -> AppCategory.NEWS_READING

            containsAny(
                "maps", ".map", "navigation", "地図", "ナビ",
            ) -> AppCategory.TRAVEL_WEATHER

            containsAny(
                "twitter", "instagram", "reddit", "facebook", "messag", "mail",
                "discord", "slack", "teams", "mastodon", "social", "電話", "メール",
                "メッセージ", "通話",
            ) -> AppCategory.SOCIAL

            containsAny(
                "authenticator", "password", "tasker", "switchbot", "tether", "smarthome",
                "tailscale", "localsend", "fdroid", "settings", "calculator", "clock",
                "files", "contacts", "safety", "terminal", "認証", "パスワード", "設定",
                "電卓", "時計", "ファイル",
            ) -> AppCategory.UTILITIES

            else -> AppCategory.OTHER
        }
    }
}
