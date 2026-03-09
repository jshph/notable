package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import java.util.Date
import java.util.UUID
import javax.inject.Inject

enum class AnnotationType {
    WIKILINK,
    TAG
}

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Annotation(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    val type: String, // "WIKILINK" or "TAG"

    // Bounding box in page coordinates
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,

    @ColumnInfo(index = true)
    val pageId: String,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

@Dao
interface AnnotationDao {
    @Insert
    suspend fun create(annotation: Annotation): Long

    @Insert
    suspend fun create(annotations: List<Annotation>)

    @Query("DELETE FROM Annotation WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM Annotation WHERE pageId = :pageId")
    suspend fun getByPageId(pageId: String): List<Annotation>

    @Transaction
    @Query("SELECT * FROM Annotation WHERE id = :annotationId")
    suspend fun getById(annotationId: String): Annotation?
}

class AnnotationRepository @Inject constructor(
    private val db: AnnotationDao
) {
    suspend fun create(annotation: Annotation): Long = db.create(annotation)
    suspend fun create(annotations: List<Annotation>) = db.create(annotations)
    suspend fun deleteAll(ids: List<String>) = db.deleteAll(ids)
    suspend fun getByPageId(pageId: String): List<Annotation> = db.getByPageId(pageId)
    suspend fun getById(annotationId: String): Annotation? = db.getById(annotationId)
}
