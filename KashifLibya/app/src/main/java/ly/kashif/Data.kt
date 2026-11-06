package ly.kashif

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "numbers")
data class NumberEntry(
    @PrimaryKey val number: String,
    val name: String = "",
    val city: String = "",
    val spam: Boolean = false,
    val reports: Int = 0,
    val blocked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface NumberDao {
    @Query("SELECT * FROM numbers WHERE number = :n") suspend fun find(n: String): NumberEntry?
    @Query("SELECT * FROM numbers") fun all(): Flow<List<NumberEntry>>
    @Query("SELECT COUNT(*) FROM numbers") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: NumberEntry)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(l: List<NumberEntry>)
}

@Database(entities = [NumberEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NumberDao
    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "kashif.db").build().also { inst = it }
        }
    }
}

object NumberUtil {
    /** يحوّل +218912345678 أو 00218912345678 أو 912345678 إلى 0912345678 */
    fun normalize(raw: String): String {
        var s = raw.filter { it.isDigit() }
        if (s.startsWith("00218")) s = s.removePrefix("00218")
        else if (s.startsWith("218")) s = s.removePrefix("218")
        if (s.length == 9 && !s.startsWith("0")) s = "0$s"
        return s
    }
    fun pretty(n: String): String {
        val s = if (n.length == 10) "${n.take(3)} ${n.substring(3, 6)} ${n.substring(6)}" else n
        return "\u2066$s\u2069"
    }
}

object Seed {
    val list = listOf(
        NumberEntry("0912345678", "أحمد المصراتي", "طرابلس"),
        NumberEntry("0944455566", "مكتب الصيرفة", "بنغازي"),
        NumberEntry("0921112233", "عروض سريعة - تسويق", "", spam = true, reports = 214),
        NumberEntry("0917778899", "احتيال رصيد", "", spam = true, reports = 487)
    )
}
