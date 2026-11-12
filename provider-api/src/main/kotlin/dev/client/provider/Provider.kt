package dev.client.provider

/**
 * Версия контракта провайдера. Клиент и плагин сверяются по мажорной версии:
 * несовпадение мажора = плагин несовместим, показываем предупреждение и не грузим его.
 */
object ContractVersion {
    const val MAJOR = 1
    const val MINOR = 0
}

/** Что умеет конкретный провайдер. Клиент скрывает в UI то, что провайдер не поддерживает. */
data class Capabilities(
    val feed: Boolean = false,
    val search: Boolean = false,
    val comments: Boolean = false,
    val videoByUrl: Boolean = true,
    val profile: Boolean = false,
)

data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class VideoItem(
    val id: String,
    val playUrl: String,
    val coverUrl: String?,
    val description: String,
    val author: Author,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
)

data class Comment(
    val id: String,
    val author: Author,
    val text: String,
    val likeCount: Long = 0,
    val replyCount: Long = 0,
)

/** Постраничная выдача: курсор непрозрачен для клиента, хранит и передаёт как есть. */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

sealed class ProviderError : Exception() {
    object NeedsLogin : ProviderError()
    object RateLimited : ProviderError()
    object Unavailable : ProviderError()
    data class Unknown(override val message: String) : ProviderError()
}

/**
 * Единственная граница между клиентом и источником данных.
 * Клиент работает только с этим интерфейсом и не знает, что у него внутри:
 * моковые данные, официальный API, встроенный плеер или сторонний плагин.
 */
interface Provider {
    val id: String
    val displayName: String
    val capabilities: Capabilities

    suspend fun getFeed(cursor: String?): Page<VideoItem>
    suspend fun search(query: String, cursor: String?): Page<VideoItem>
    suspend fun getVideo(id: String): VideoItem
    suspend fun getComments(videoId: String, cursor: String?): Page<Comment>
    suspend fun getProfile(id: String): Author
}
