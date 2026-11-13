package app.vault.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "performers", indices = [Index("name")])
data class Performer(
    @PrimaryKey val id: String,
    val name: String,
    val aliases: String? = null,
    val gender: String? = null,
    val birthday: String? = null, // yyyy-MM-dd
    val careerStart: Int? = null,
    val careerEnd: Int? = null,
    val ethnicity: String? = null,
    val nationality: String? = null,
    val hair: String? = null,
    val eyes: String? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null,
    val measurements: String? = null,
    val cup: String? = null,
    val tattoos: String? = null,
    val piercings: String? = null,
    val bio: String? = null,
    val image: String? = null,
    val thumb: String? = null,
    val sceneCount: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val performerId: String, val addedAt: Long = System.currentTimeMillis())

@Entity(tableName = "tags", primaryKeys = ["performerId", "tag"])
data class Tag(val performerId: String, val tag: String)

@Entity(tableName = "sites")
data class Site(
    @PrimaryKey val domain: String,
    val title: String,
    val url: String,
    /** Custom search link, with {q} where the name goes. Null = search engine "site:" search. */
    val searchTemplate: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface PerformerDao {
    @Upsert suspend fun upsert(list: List<Performer>)

    @Query("SELECT * FROM performers WHERE id = :id") fun observe(id: String): Flow<Performer?>
    @Query("SELECT * FROM performers WHERE id = :id") suspend fun get(id: String): Performer?

    @RawQuery(observedEntities = [Performer::class, Favorite::class, Tag::class])
    fun query(q: SupportSQLiteQuery): Flow<List<Performer>>

    @Query("SELECT COUNT(*) FROM performers") fun count(): Flow<Int>

    @Query("SELECT DISTINCT gender FROM performers WHERE gender IS NOT NULL AND gender <> '' ORDER BY gender") fun genders(): Flow<List<String>>
    @Query("SELECT DISTINCT ethnicity FROM performers WHERE ethnicity IS NOT NULL AND ethnicity <> '' ORDER BY ethnicity") fun ethnicities(): Flow<List<String>>
    @Query("SELECT DISTINCT nationality FROM performers WHERE nationality IS NOT NULL AND nationality <> '' ORDER BY nationality") fun nationalities(): Flow<List<String>>
    @Query("SELECT DISTINCT hair FROM performers WHERE hair IS NOT NULL AND hair <> '' ORDER BY hair") fun hairColors(): Flow<List<String>>
    @Query("SELECT DISTINCT eyes FROM performers WHERE eyes IS NOT NULL AND eyes <> '' ORDER BY eyes") fun eyeColors(): Flow<List<String>>
    @Query("SELECT DISTINCT cup FROM performers WHERE cup IS NOT NULL AND cup <> '' ORDER BY cup") fun cups(): Flow<List<String>>
    @Query("SELECT DISTINCT tag FROM tags ORDER BY tag COLLATE NOCASE") fun allTags(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE performerId = :id)") fun isFavorite(id: String): Flow<Boolean>
    @Query("SELECT performerId FROM favorites") fun favoriteIdsFlow(): Flow<List<String>>
    @Query("SELECT performerId FROM favorites") suspend fun favoriteIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavorite(f: Favorite)
    @Query("DELETE FROM favorites WHERE performerId = :id") suspend fun removeFavorite(id: String)

    @Query("SELECT tag FROM tags WHERE performerId = :id ORDER BY tag COLLATE NOCASE") fun tagsFor(id: String): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTag(t: Tag)
    @Query("DELETE FROM tags WHERE performerId = :id AND tag = :tag") suspend fun removeTag(id: String, tag: String)
}

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY title COLLATE NOCASE") fun all(): Flow<List<Site>>
    @Query("SELECT * FROM sites") suspend fun list(): List<Site>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(list: List<Site>): List<Long>
    @Upsert suspend fun upsert(site: Site)
    @Delete suspend fun delete(site: Site)
    @Query("DELETE FROM sites") suspend fun clear()
}

@Database(entities = [Performer::class, Favorite::class, Tag::class, Site::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun performers(): PerformerDao
    abstract fun sites(): SiteDao
}
