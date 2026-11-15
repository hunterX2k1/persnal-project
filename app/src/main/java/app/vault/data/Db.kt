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
    /** Cup letters only, e.g. "DD". */
    val cup: String? = null,
    val bust: Int? = null,
    val waist: Int? = null,
    val hips: Int? = null,
    /** 1 = natural, 0 = enhanced, null = unknown. */
    val natural: Int? = null,
    val zodiac: String? = null,
    val birthplace: String? = null,
    val tattoos: String? = null,
    val piercings: String? = null,
    val bio: String? = null,
    val image: String? = null,
    val thumb: String? = null,
    val sceneCount: Int? = null,
    /** English Wikipedia article (free source only). */
    val wiki: String? = null,
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
    /** Searched when looking for a video across my sites. */
    @ColumnInfo(defaultValue = "1") val inVideoSearch: Boolean = true,
    /** When automatic search-link detection last ran (0 = never). */
    @ColumnInfo(defaultValue = "0") val searchCheckedAt: Long = 0,
    /** What the site is: video, images, comics, stories, games, forum, other (null = not scanned yet). */
    val kind: String? = null,
    /** Added automatically because it had a video I searched for. */
    @ColumnInfo(defaultValue = "0") val discovered: Boolean = false,
)

@Dao
interface PerformerDao {
    @Upsert suspend fun upsert(list: List<Performer>)

    @Query("SELECT * FROM performers WHERE id = :id") fun observe(id: String): Flow<Performer?>
    @Query("SELECT * FROM performers WHERE id = :id") suspend fun get(id: String): Performer?

    // ---- merging the free source with ThePornDB (same person, two ids) ----
    @Query("SELECT id FROM performers WHERE id LIKE 'wd:%' AND name = :name COLLATE NOCASE")
    suspend fun freeIdsNamed(name: String): List<String>
    @Query("SELECT LOWER(name) FROM performers WHERE id NOT LIKE 'wd:%' AND LOWER(name) IN (:lowerNames)")
    suspend fun paidNamesIn(lowerNames: List<String>): List<String>
    @Query("UPDATE OR IGNORE favorites SET performerId = :to WHERE performerId = :from") suspend fun moveFavorites(from: String, to: String)
    @Query("UPDATE OR IGNORE tags SET performerId = :to WHERE performerId = :from") suspend fun moveTags(from: String, to: String)
    @Query("DELETE FROM favorites WHERE performerId = :id") suspend fun deleteFavoritesOf(id: String)
    @Query("DELETE FROM tags WHERE performerId = :id") suspend fun deleteTagsOf(id: String)
    @Query("DELETE FROM performers WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE performers SET bio = :bio WHERE id = :id") suspend fun setBio(id: String, bio: String)

    @RawQuery(observedEntities = [Performer::class, Favorite::class, Tag::class])
    fun query(q: SupportSQLiteQuery): Flow<List<Performer>>

    @Query("SELECT COUNT(*) FROM performers") fun count(): Flow<Int>
    @Query("SELECT name FROM performers WHERE id NOT LIKE 'wd:%' ORDER BY (sceneCount IS NULL), sceneCount DESC, (image IS NULL) LIMIT 1") suspend fun samplePerformerName(): String?
    @Query("SELECT id, name, aliases, thumb, image FROM performers WHERE birthday IS NULL OR birthday <= date('now', '-18 years')") suspend fun nameIndex(): List<NameRow>
    @Query("SELECT name FROM performers WHERE name LIKE :prefix || '%' ORDER BY name COLLATE NOCASE LIMIT 8") suspend fun namesStarting(prefix: String): List<String>

    @Query("SELECT DISTINCT gender FROM performers WHERE gender IS NOT NULL AND gender <> '' ORDER BY gender") fun genders(): Flow<List<String>>
    @Query("SELECT DISTINCT ethnicity FROM performers WHERE ethnicity IS NOT NULL AND ethnicity <> '' ORDER BY ethnicity") fun ethnicities(): Flow<List<String>>
    @Query("SELECT DISTINCT nationality FROM performers WHERE nationality IS NOT NULL AND nationality <> '' ORDER BY nationality") fun nationalities(): Flow<List<String>>
    @Query("SELECT DISTINCT hair FROM performers WHERE hair IS NOT NULL AND hair <> '' ORDER BY hair") fun hairColors(): Flow<List<String>>
    @Query("SELECT DISTINCT eyes FROM performers WHERE eyes IS NOT NULL AND eyes <> '' ORDER BY eyes") fun eyeColors(): Flow<List<String>>
    @Query("SELECT DISTINCT cup FROM performers WHERE cup IS NOT NULL AND cup <> '' ORDER BY cup") fun cups(): Flow<List<String>>
    @Query("SELECT DISTINCT tag FROM tags ORDER BY tag COLLATE NOCASE") fun allTags(): Flow<List<String>>
    @Query("SELECT DISTINCT zodiac FROM performers WHERE zodiac IS NOT NULL AND zodiac <> '' ORDER BY zodiac") fun zodiacs(): Flow<List<String>>

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
    @Query("SELECT * FROM sites WHERE domain = :d") suspend fun get(d: String): Site?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(list: List<Site>): List<Long>
    @Upsert suspend fun upsert(site: Site)
    @Delete suspend fun delete(site: Site)
    @Query("DELETE FROM sites") suspend fun clear()
}

@Database(entities = [Performer::class, Favorite::class, Tag::class, Site::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun performers(): PerformerDao
    abstract fun sites(): SiteDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE performers ADD COLUMN wiki TEXT")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for (col in listOf("bust INTEGER", "waist INTEGER", "hips INTEGER", "natural INTEGER", "zodiac TEXT", "birthplace TEXT"))
                    db.execSQL("ALTER TABLE performers ADD COLUMN $col")
                db.execSQL("ALTER TABLE sites ADD COLUMN inVideoSearch INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sites ADD COLUMN searchCheckedAt INTEGER NOT NULL DEFAULT 0")
                // Old rows stored "35DD"-style cup sizes; they are rewritten on the next sync.
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sites ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE sites ADD COLUMN discovered INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
