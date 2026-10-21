package androidx.room
// Stand-ins mirroring Room 2.8's public API (annotations + builder).
import kotlin.reflect.KClass
@Target(AnnotationTarget.CLASS) annotation class Entity(val tableName: String = "", val indices: Array<Index> = [], val primaryKeys: Array<String> = [])
annotation class Index(vararg val value: String, val name: String = "", val unique: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val onConflict: Int = 3)
@Target(AnnotationTarget.FUNCTION) annotation class Upsert
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.CLASS) annotation class Database(val entities: Array<KClass<*>> = [], val version: Int, val exportSchema: Boolean = true)
abstract class RoomDatabase {
    class Builder<T : RoomDatabase> { fun build(): T = throw UnsupportedOperationException() }
}
object Room {
    fun <T : RoomDatabase> databaseBuilder(context: android.content.Context, klass: Class<T>, name: String?): RoomDatabase.Builder<T> = RoomDatabase.Builder()
}
